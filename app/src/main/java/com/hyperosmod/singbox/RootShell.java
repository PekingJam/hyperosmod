package com.hyperosmod.singbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 以 root 身份执行命令。
 *
 * <p>sing-box 管理与 hook 功能不同：它不是注入别的进程，而是在本应用里直接 {@code su -c}
 * 起子进程，所以要求用户在 root 管理器里给本应用授权。</p>
 *
 * <p>读输出放在独立线程里、外层再按超时等待：{@code su} 拉起的后台进程有时会继承
 * stdout 管道不释放，直接在主流程里 readLine 会一直卡住，界面就永远停在「忙」的状态。</p>
 */
public final class RootShell {

    /** 执行结果。{@code code == -1} 表示超时，{@code -2} 表示拿不到 root。 */
    public static final class Result {
        public final int code;
        public final String output;

        public Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    private RootShell() {
    }

    /** 是否有可用的 root。用一次 {@code id} 判定，失败即认为没有授权。 */
    public static boolean hasRoot() {
        Result result = exec("id", 8);
        return result.ok() && result.output.contains("uid=0");
    }

    public static Result exec(String command, int timeoutSeconds) {
        Process process = null;
        StringBuilder builder = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final Process target = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                        target.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stream.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                } catch (Throwable ignored) {
                    // 进程被杀时读线程会抛异常，忽略即可
                }
            }, "rootshell-reader");
            reader.setDaemon(true);
            reader.start();

            try (OutputStream stdin = process.getOutputStream()) {
                // 有些 su 实现会等一个换行才执行，写一个空行更稳。
                stdin.write('\n');
                stdin.flush();
            } catch (Throwable ignored) {
                // 关掉了也无所谓，命令已经在跑
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            // 给读线程一点时间收尾，但绝不无限等它。
            reader.join(2000);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, builder + "\n（执行超时，已强制结束）");
            }
            return new Result(process.exitValue(), builder.toString().trim());
        } catch (Throwable t) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(-2, "无法获取 root：" + t.getMessage());
        }
    }

    /**
     * 把一段脚本写进文件并执行。
     *
     * <p>用 {@code base64} 中转，避免引号、换行、中文在 {@code su -c} 的多层转义里被吃掉。</p>
     */
    public static Result execScript(String scriptBody, int timeoutSeconds) {
        try {
            String encoded = android.util.Base64.encodeToString(
                    scriptBody.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            return exec("echo " + encoded + " | base64 -d | sh", timeoutSeconds);
        } catch (Throwable t) {
            return new Result(-2, "构造脚本失败：" + t.getMessage());
        }
    }
}
