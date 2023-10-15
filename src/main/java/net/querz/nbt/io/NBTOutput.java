package net.querz.nbt.io;

import java.io.IOException;

public interface NBTOutput {

  void writeTag(NamedTag tag, int maxDepth) throws IOException;

  void flush() throws IOException;
}
