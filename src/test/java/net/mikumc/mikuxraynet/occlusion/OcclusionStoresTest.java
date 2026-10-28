package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * {@link OcclusionStores} 的分域语义测试：<b>同名区块在不同世界互不影响</b>。
 *
 * <p>这是「不分域就会把可见实体误藏」这一安全缺陷的守门用例：若哪天把外层维度去掉，本类会立刻失败。
 */
class OcclusionStoresTest {

  @Test
  void sameCoordinatesInDifferentWorldsDoNotLeak() {
    OcclusionStores stores = new OcclusionStores();
    // 主世界 (0,0) 实心；下界同名区块一片空
    stores.of("world").replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);

    assertSame(SolidOcclusion.INSTANCE, stores.of("world").section(0, 0, 0),
        "主世界该段应保留自己的实心位图");
    assertNull(stores.lookup("world_nether"),
        "只写过主世界时，下界不应凭空出现一张表");
    assertNull(stores.of("world_nether").section(0, 0, 0),
        "下界同名区块必须查不到数据（fail-open → 可见），绝不套用主世界的位图");
  }

  @Test
  void writesToDifferentWorldsLandInDifferentTables() {
    OcclusionStores stores = new OcclusionStores();
    stores.of("world").replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    BitmapOcclusion hole = new BitmapOcclusion();
    hole.setOccluding(0, 0, 0, true);
    stores.of("world_nether").replaceSection(0, 0, 0, hole);

    assertSame(SolidOcclusion.INSTANCE, stores.of("world").section(0, 0, 0),
        "主世界的表不得被下界的写入覆盖");
    assertSame(hole, stores.of("world_nether").section(0, 0, 0),
        "下界必须持有自己写入的那份位图");
    assertNotSame(stores.of("world"), stores.of("world_nether"),
        "不同世界必须是彼此独立的仓库实例");
    assertEquals(2, stores.worldCount());
  }

  @Test
  void lookupDoesNotCreateAndIsNullSafe() {
    OcclusionStores stores = new OcclusionStores();
    assertNull(stores.lookup("world"), "只读查询不得建表");
    assertEquals(0, stores.worldCount());
    assertNull(stores.lookup(null), "世界名为 null 时按「查不到」处理，不得抛异常");
    // of(null) 给出一张独立仓库：不得与真实世界混用
    assertNotNull(stores.of(null), "of(null) 必须给出一张独立仓库（供写入方使用）");
    assertNull(stores.lookup("world"), "of(null) 不得污染真实世界的键");
  }

  @Test
  void removeWorldReleasesOnlyThatWorld() {
    OcclusionStores stores = new OcclusionStores();
    stores.of("world").replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    stores.of("world_nether").replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    assertEquals(2, stores.residentSections());

    stores.removeWorld("world_nether");
    assertEquals(1, stores.worldCount());
    assertNull(stores.lookup("world_nether"), "被卸载世界的表必须整体释放");
    assertSame(SolidOcclusion.INSTANCE, stores.of("world").section(0, 0, 0),
        "卸载一个世界不得影响其它世界");
    assertNull(stores.lookup(null), "世界名为 null 的摘除是 no-op");
  }

  @Test
  void removedWorldStaysRetiredUntilReactivated() {
    OcclusionStores stores = new OcclusionStores();
    stores.of("room").replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    stores.removeWorld("room");

    assertNull(stores.of("room"),
        "卸载后同名世界的在途写入不得重建表（否则旧几何会被当成新世界数据读走）");
    stores.reactivate("room");
    assertNotNull(stores.of("room"), "世界重新加载后必须能重建表（否则该世界永远没有遮挡数据）");
    assertNull(stores.of("room").section(0, 0, 0), "重建出来的是空表，不得残留旧世界的几何");
  }

  @Test
  void residentSectionsSumsAcrossWorldsAndClearEmptiesEverything() {
    OcclusionStores stores = new OcclusionStores();
    stores.of("world").replaceSection(1, 0, 1, SolidOcclusion.INSTANCE);
    stores.of("world").replaceSection(2, 0, 2, SolidOcclusion.INSTANCE);
    stores.of("world_nether").replaceSection(1, 0, 1, SolidOcclusion.INSTANCE);
    assertEquals(3, stores.residentSections(), "驻留数必须跨世界求和");

    stores.clear();
    assertEquals(0, stores.worldCount());
    assertEquals(0, stores.residentSections());
  }
}
