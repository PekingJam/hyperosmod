package com.hyperosmod.xposed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 显秒的文本处理是纯逻辑，不依赖 Android 运行时，所以在任何架构上都能跑。
 *
 * <p>本机是 aarch64，Robolectric 的 native graphics runtime 在这里跑不起来（
 * {@code The Robolectric native runtime is not supported on Linux (aarch64)}），所以视图类的
 * 冒烟测试只能交给真机；这里把最容易出错、又完全可离线验证的部分钉住。</p>
 */
public class ClockSecondsFormatTest {

    @Test
    public void appendsSecondsTo24HourClock() {
        assertEquals("09:41:07", ClockSecondsHook.insertSeconds("09:41", 7));
    }

    @Test
    public void appendsSecondsTo12HourClockWithPrefix() {
        assertEquals("上午9:41:05", ClockSecondsHook.insertSeconds("上午9:41", 5));
    }

    @Test
    public void keepsMultiLineDateAndOnlyTouchesTheTime() {
        assertEquals("09:41:05\n11/10 周一",
                ClockSecondsHook.insertSeconds("09:41\n11/10 周一", 5));
    }

    @Test
    public void padsSingleDigitSeconds() {
        assertEquals("23:59:03", ClockSecondsHook.insertSeconds("23:59", 3));
    }

    @Test
    public void leavesTextWithoutTimeUntouched() {
        assertEquals("--", ClockSecondsHook.insertSeconds("--", 9));
    }

    @Test
    public void stripSecondsRemovesTheInjectedPart() {
        assertEquals("09:41", ClockSecondsHook.stripSeconds("09:41:07"));
        assertEquals("上午9:41", ClockSecondsHook.stripSeconds("上午9:41:05"));
        assertEquals("09:41\n11/10 周一", ClockSecondsHook.stripSeconds("09:41:05\n11/10 周一"));
    }

    @Test
    public void stripSecondsIsIdempotentForPlainTime() {
        assertEquals("09:41", ClockSecondsHook.stripSeconds("09:41"));
    }

    /** 插入再剥离必须回到原文，否则秒会一轮轮越叠越长。 */
    @Test
    public void insertThenStripRoundTrips() {
        String[] samples = {"09:41", "上午9:41", "09:41\n11/10 周一", "23:59"};
        for (String sample : samples) {
            String withSeconds = ClockSecondsHook.insertSeconds(sample, 42);
            assertEquals(sample, ClockSecondsHook.stripSeconds(withSeconds));
        }
    }
}
