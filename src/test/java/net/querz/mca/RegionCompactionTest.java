package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import org.junit.Test;
import tc.oc.occ.autopruner.AutoPruner;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the "check for gaps first" defragmentation: {@link MCAFile#analyzeLayout(byte[])}
 * detects wasted sectors (gaps, trailing padding, over-allocated slots), and
 * {@link AutoPruner#pruneMCAFile(String, java.util.function.Consumer, java.util.function.Consumer)}
 * rewrites a fragmented file to reclaim them while leaving an already-tight file untouched.
 */
public class RegionCompactionTest {

  private static final int SECTOR = 4096;
  private static final int DV_1_21 = 4556;

  // ---------------------------------------------------------------------------------------------
  // Gap detection (analyzeLayout)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void tightlyPackedFileHasNoReclaimableSpace() {
    // 1 chunk in sector 2 (right after the 2-sector header), file is exactly 3 sectors.
    MCAFile mca = analyze(region(3, 2, 1, 100));
    assertFalse(mca.hasReclaimableSpace());
    assertEquals(0, mca.getReclaimableSectors());
  }

  @Test
  public void gapBetweenHeaderAndChunkIsReclaimable() {
    // Chunk lives in sector 3, leaving sector 2 as an unused gap.
    MCAFile mca = analyze(region(4, 3, 1, 100));
    assertTrue(mca.hasReclaimableSpace());
    assertEquals(1, mca.getReclaimableSectors());
  }

  @Test
  public void trailingPaddingIsReclaimable() {
    // 1 chunk needing 1 sector, but the file carries 2 extra trailing sectors.
    MCAFile mca = analyze(region(5, 2, 1, 100));
    assertTrue(mca.hasReclaimableSpace());
    assertEquals(2, mca.getReclaimableSectors());
  }

  @Test
  public void overAllocatedSlotIsReclaimable() {
    // The slot reserves 3 sectors but the chunk's declared data needs only 1.
    MCAFile mca = analyze(region(5, 2, 3, 100));
    assertTrue(mca.hasReclaimableSpace());
    assertEquals(2, mca.getReclaimableSectors());
  }

  // ---------------------------------------------------------------------------------------------
  // End-to-end compaction
  // ---------------------------------------------------------------------------------------------

  @Test
  public void fragmentedRegionFileIsCompactedAndStable() throws IOException {
    Path tmp = Files.createTempDirectory("autopruner-compact");
    File region = new File(tmp.toFile(), "r.0.0.mca");

    MCAFile mca = new MCAFile(0, 0);
    mca.setChunk(0, new Chunk(modernStoneChunk()));
    MCAUtil.write(mca, region.getAbsolutePath());
    long packedSize = region.length();

    // Simulate fragmentation by appending trailing waste sectors.
    try (RandomAccessFile raf = new RandomAccessFile(region, "rw")) {
      raf.setLength(packedSize + 3L * SECTOR);
    }
    long bloatedSize = region.length();
    assertTrue(bloatedSize > packedSize);
    assertTrue("waste should be detected", MCAUtil.read(region).hasReclaimableSpace());

    List<String> info = new ArrayList<>();
    AutoPruner.pruneMCAFile(region.getAbsolutePath(), info::add, message -> { });

    assertTrue("a fragmented file should be compacted: " + info,
        info.stream().anyMatch(message -> message.startsWith("Compacted")));
    assertEquals("compacting should restore the tight packing", packedSize, region.length());

    MCAFile after = MCAUtil.read(region);
    assertNotNull("the surviving chunk must be preserved", after.getChunk(0));
    assertFalse("a freshly compacted file has no further waste", after.hasReclaimableSpace());

    // Idempotent: a second prune leaves the already-tight file untouched.
    List<String> second = new ArrayList<>();
    AutoPruner.pruneMCAFile(region.getAbsolutePath(), second::add, message -> { });
    assertTrue("second run should skip the already-tight file: " + second,
        second.stream().anyMatch(message -> message.startsWith("Skipping already Pruned File")));

    Files.deleteIfExists(region.toPath());
    Files.deleteIfExists(tmp);
  }

  // ---------------------------------------------------------------------------------------------
  // Builders
  // ---------------------------------------------------------------------------------------------

  private static MCAFile analyze(byte[] raw) {
    MCAFile mca = new MCAFile(0, 0);
    mca.analyzeLayout(raw);
    return mca;
  }

  /** A region image with a single chunk slot, used to exercise {@link MCAFile#analyzeLayout}. */
  private static byte[] region(int totalSectors, int offsetSectors, int sectorCount, int declaredLen) {
    byte[] raw = new byte[totalSectors * SECTOR];
    raw[0] = (byte) (offsetSectors >>> 16);
    raw[1] = (byte) (offsetSectors >>> 8);
    raw[2] = (byte) offsetSectors;
    raw[3] = (byte) sectorCount;
    int dataPos = offsetSectors * SECTOR;
    raw[dataPos] = (byte) (declaredLen >>> 24);
    raw[dataPos + 1] = (byte) (declaredLen >>> 16);
    raw[dataPos + 2] = (byte) (declaredLen >>> 8);
    raw[dataPos + 3] = (byte) declaredLen;
    return raw;
  }

  private static CompoundTag modernStoneChunk() {
    CompoundTag data = new CompoundTag();
    data.putInt("DataVersion", DV_1_21);
    CompoundTag section = new CompoundTag();
    section.putByte("Y", (byte) 0);
    ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
    CompoundTag stone = new CompoundTag();
    stone.put("Name", new StringTag("minecraft:stone"));
    palette.add(stone);
    CompoundTag blockStates = new CompoundTag();
    blockStates.put("palette", palette);
    section.put("block_states", blockStates);
    ListTag<CompoundTag> sections = new ListTag<>(CompoundTag.class);
    sections.add(section);
    data.put("sections", sections);
    return data;
  }
}
