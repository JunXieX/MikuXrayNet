package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 「清单 ⊆ 标记」不变式的唯一维护入口：注销、覆盖差集、区块/世界失效、容量淘汰都必须同时清标记。
 */
class ChunkRevealBookTest {

  private static final UUID PLAYER = UUID.randomUUID();

  private static ChunkRevealBook book(ObfuscatedChunkIndex index, RevealedSet marks) {
    return new ChunkRevealBook(() -> index, () -> marks);
  }

  @Test
  void removingPositionsClearsMarksToo() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1000, 60);
    RevealedSet marks = new RevealedSet(1000, 60);
    ChunkRevealBook book = book(index, marks);
    ChunkKey key = new ChunkKey("w", 0, 0);
    book.recordChunk("w", 0, 0, 0, new int[] {SectionCoords.pack(1, 0, 0)});
    assertTrue(marks.markIfAbsent(PLAYER, key, 1, 0, 0));

    assertEquals(1, book.removePosition("w", 1, 0, 0) ? 1 : 0);
    assertFalse(marks.contains(PLAYER, key, 1, 0, 0), "注销坐标必须同时摘除标记");
  }

  @Test
  void overwritingWithSmallerListClearsRemovedMarks() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1000, 60);
    RevealedSet marks = new RevealedSet(1000, 60);
    ChunkRevealBook book = book(index, marks);
    ChunkKey key = new ChunkKey("w", 0, 0);
    book.recordChunk("w", 0, 0, 0,
        new int[] {SectionCoords.pack(1, 0, 0), SectionCoords.pack(2, 0, 0)});
    assertTrue(marks.markIfAbsent(PLAYER, key, 1, 0, 0));

    int[] removed = book.recordChunk("w", 0, 0, 0, new int[] {SectionCoords.pack(2, 0, 0)});
    assertEquals(1, removed.length);
    assertFalse(marks.contains(PLAYER, key, 1, 0, 0), "被移出清单的坐标必须摘标记");
  }

  @Test
  void invalidatingChunkClearsMarksEvenWhenListingIsAbsent() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1000, 60);
    RevealedSet marks = new RevealedSet(1000, 60);
    ChunkRevealBook book = book(index, marks);
    ChunkKey key = new ChunkKey("w", 3, -2);
    assertTrue(marks.markIfAbsent(PLAYER, key, 5, 6, 7));
    assertFalse(book.invalidateChunk("w", 3, -2), "清单本就不存在：返回 false");
    assertFalse(marks.contains(PLAYER, key, 5, 6, 7), "区块卸载后标记必须无条件清掉");
  }

  @Test
  void invalidatingWorldClearsMarks() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1000, 60);
    RevealedSet marks = new RevealedSet(1000, 60);
    ChunkRevealBook book = book(index, marks);
    ChunkKey key = new ChunkKey("w", 0, 0);
    book.recordChunk("w", 0, 0, 0, new int[] {SectionCoords.pack(1, 1, 1)});
    assertTrue(marks.markIfAbsent(PLAYER, key, 1, 1, 1));

    book.invalidateWorld("w");
    assertNull(index.entry(key), "世界失效必须摘索引");
    assertFalse(marks.contains(PLAYER, key, 1, 1, 1), "世界失效必须摘标记");
  }

  @Test
  void capacityEvictionClearsMarksOfEvictedChunks() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(2, 60);
    RevealedSet marks = new RevealedSet(1000, 60);
    ChunkRevealBook book = book(index, marks);
    ChunkKey first = new ChunkKey("w", 0, 0);
    book.recordChunk("w", 0, 0, 0,
        new int[] {SectionCoords.pack(1, 0, 0), SectionCoords.pack(2, 0, 0)});
    assertTrue(marks.markIfAbsent(PLAYER, first, 1, 0, 0));

    // 第二个区块使坐标总量触顶 → 安全阀按最旧优先淘汰第一个区块
    book.recordChunk("w", 1, 0, 0,
        new int[] {SectionCoords.pack(1, 0, 0), SectionCoords.pack(2, 0, 0)});
    assertFalse(marks.contains(PLAYER, first, 1, 0, 0), "被容量淘汰的区块必须摘标记");
  }

  @Test
  void absoluteConversionMatchesRelativeEncoding() {
    int[] locals = {SectionCoords.pack(3, 5, 7), SectionCoords.pack(15, 0, 1)};
    int[] absolute = ChunkRevealBook.toAbsolute(-2, 3, -64, locals);
    assertEquals(6, absolute.length);
    assertEquals((-2 << 4) | 3, absolute[0]);
    assertEquals(-64 + 5, absolute[1]);
    assertEquals((3 << 4) | 7, absolute[2]);
    assertEquals((-2 << 4) | 15, absolute[3]);
    assertEquals(-64, absolute[4]);
    assertEquals((3 << 4) | 1, absolute[5]);
  }
}
