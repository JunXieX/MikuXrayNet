package net.mikumc.mikuxraynet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线自检：确保测试阶段被 CI 真正执行（防止「只编译不测试」的假绿）。
 */
class BuildPipelineTest {

    @Test
    void testPipelineIsExecuted() {
        assertTrue(true, "测试流水线可用");
    }
}