package tc.oc.occ.autopruner;

import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Minecraft sometimes leaves behind zero-byte region files. Reading one must yield an empty region
 * rather than throwing, so the pruner treats it as fail-safe cleanup instead of a parse error.
 */
public class EmptyRegionFileTest {

  @Test
  public void zeroByteRegionFileReadsAsEmpty() throws IOException {
    Path tmp = Files.createTempDirectory("autopruner-empty-read");
    File region = new File(tmp.toFile(), "r.0.0.mca");
    Files.createFile(region.toPath());

    MCAFile mca = MCAUtil.read(region);
    for (int i = 0; i < MCAFile.CHUNK_COUNT; i++) {
      assertNull("zero-byte region must contain no chunks", mca.getChunk(i));
    }
    Files.delete(region.toPath());
    Files.delete(tmp);
  }

  @Test
  public void pruningZeroByteRegionFileDeletesItWithoutWarning() throws IOException {
    Path tmp = Files.createTempDirectory("autopruner-empty-prune");
    File region = new File(tmp.toFile(), "r.0.0.mca");
    Files.createFile(region.toPath());

    List<String> warnings = new ArrayList<>();
    AutoPruner.pruneMCAFile(region.getAbsolutePath(), message -> { }, warnings::add);

    assertTrue("an empty region must not warn, but got: " + warnings, warnings.isEmpty());
    assertFalse("an empty region file should be removed", region.exists());
    Files.delete(tmp);
  }

  @Test
  public void dryRunReportsButDoesNotDeleteFile() throws IOException {
    Path tmp = Files.createTempDirectory("autopruner-dry-run");
    File region = new File(tmp.toFile(), "r.0.0.mca");
    Files.createFile(region.toPath());

    List<String> info = new ArrayList<>();
    AutoPruner.pruneMCAFile(region.getAbsolutePath(), info::add, message -> { }, true);

    assertTrue("dry run must leave the file in place", region.exists());
    assertTrue("dry run should report the would-be deletion: " + info,
        info.stream().anyMatch(message -> message.startsWith("Would delete file")));
    Files.delete(region.toPath());
    Files.delete(tmp);
  }
}
