package com.hyperosmod.singbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 版本比较：只有服务器比本地新才提示更新。 */
public final class SingBoxVersionCompareTest {

    @Test
    public void ignoresVPrefixAndBuildMetadata() {
        assertEquals(0, SingBoxController.compareVersions(
                "v1.15.0-alpha.13+0f06352a5", "1.15.0-alpha.13"));
        assertEquals(0, SingBoxController.compareVersions(
                "v1.15.0-alpha.13+0f06352a5", "v1.15.0-alpha.13+fffffff"));
    }

    @Test
    public void prereleaseNumbersCompareNumerically() {
        assertTrue(SingBoxController.compareVersions("1.15.0-alpha.13", "1.15.0-alpha.9") > 0);
        assertTrue(SingBoxController.compareVersions("1.15.0-alpha.9", "1.15.0-alpha.13") < 0);
    }

    @Test
    public void releaseIsNewerThanPrerelease() {
        assertTrue(SingBoxController.compareVersions("1.15.0", "1.15.0-alpha.13") > 0);
        assertTrue(SingBoxController.compareVersions("1.15.0-rc.1", "1.15.0") < 0);
    }

    @Test
    public void prereleaseStagesAreOrdered() {
        assertTrue(SingBoxController.compareVersions("1.15.0-beta.1", "1.15.0-alpha.13") > 0);
        assertTrue(SingBoxController.compareVersions("1.15.0-rc.1", "1.15.0-beta.2") > 0);
    }

    @Test
    public void coreNumbersWin() {
        assertTrue(SingBoxController.compareVersions("1.15.0-alpha.1", "1.14.9") > 0);
        assertTrue(SingBoxController.compareVersions("2.0.0", "1.99.99") > 0);
        // git describe 形态表示该 tag 之后还有提交，比 tag 本身新
        assertTrue(SingBoxController.compareVersions("1.14.4-132-g0f06352a5", "1.14.4") > 0);
        assertTrue(SingBoxController.compareVersions("1.14.4", "1.14.4-132-g0f06352a5") < 0);
    }

    @Test
    public void unparsableInstalledVersionCountsAsZero() {
        assertTrue(SingBoxController.isNewerThanInstalled(release("v1.15.0-alpha.13+0f06352a5"), "—"));
        assertTrue(SingBoxController.isNewerThanInstalled(release("v1.15.0-alpha.13+0f06352a5"), "未安装"));
        assertTrue(SingBoxController.isNewerThanInstalled(release("v1.15.0-alpha.13+0f06352a5"), "1.14.4"));
    }

    @Test
    public void sameVersionMeansNoUpdate() {
        assertFalse(SingBoxController.isNewerThanInstalled(
                release("v1.15.0-alpha.13+0f06352a5"), "v1.15.0-alpha.13+0f06352a5"));
        assertFalse(SingBoxController.isNewerThanInstalled(release("1.14.4"), "1.15.0-alpha.13"));
    }

    private static SingBoxController.Release release(String tag) {
        return new SingBoxController.Release(tag, tag.contains("-"),
                "https://example.com/sing-box.tar.gz", "sing-box.tar.gz", "", true);
    }
}
