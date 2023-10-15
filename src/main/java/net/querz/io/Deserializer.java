package net.querz.io;

import java.io.IOException;
import java.io.InputStream;

public interface Deserializer<T> {
  T fromStream(InputStream stream) throws IOException;
}
