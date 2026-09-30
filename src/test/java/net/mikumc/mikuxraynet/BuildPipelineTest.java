package net.mikumc.mikuxraynet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线自检：确保测试阶段被 CI 真正执行（防止「只编译不测试」的假绿）。
 */
class BuildPipelineTest {

    @Test
    void testPipelineIsExecuted() {
        // 有意义的不变量（替代原恒真断言 assertTrue(true,...)——那等于没断言）：
        // 钉住「测试必须在 Java 25+ 运行时执行」，既证明测试阶段确被执行过（空跑/只编译不测试会暴露），
        // 又能在运行环境漂移（JRE 版本低于构建基线）时第一时间报警。
        assertTrue(Runtime.version().feature() >= 25,
            "测试必须在 Java 25+ 上运行（当前 " + Runtime.version() + "）");
    }
}