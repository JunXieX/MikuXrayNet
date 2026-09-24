package net.mikumc.mikuxraynet;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * MikuXrayNet 插件主类。
 *
 * <p>本插件在 Paper 26.x 上提供两项能力：
 * <ul>
 *   <li><b>反矿透</b>：出站区块封包改写，把暴露于空气的矿物替换为伪装方块；</li>
 *   <li><b>带宽优化</b>：零位移包抑制、方块变更合并、调色板压缩重排、实体剔除与 AFK 降级。</li>
 * </ul>
 *
 * <p><b>封包通道</b>：ProtocolLib 是唯一的拦截与改写通道（真异步扣包，工作线程在 Netty 管道之外运行）；
 * PacketEvents 只作为「库」在启动期提供方块状态映射，运行期不参与任何拦截。
 *
 * <p>装配细节见 {@code bootstrap} 包；本类只负责生命周期编排，不承载算法。
 */
public final class MikuXrayNet extends JavaPlugin {

    @Override
    public void onLoad() {
        // 预留：配置初始化
    }

    @Override
    public void onEnable() {
        getLogger().info("MikuXrayNet 已启用（骨架版本，功能模块装配中）");
    }

    @Override
    public void onDisable() {
        getLogger().info("MikuXrayNet 已停用");
    }
}