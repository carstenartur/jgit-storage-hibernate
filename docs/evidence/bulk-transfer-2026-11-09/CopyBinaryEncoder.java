import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

public final class CopyBinaryEncoder {
  public static void main(String[] args) throws Exception {
    final int chunk = 1024 * 1024;
    byte[] data = new byte[2 * chunk + 257];
    new Random(189).nextBytes(data);
    long packId = Long.parseLong(args[1]);
    String mode = args[2];
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[0]))))) {
      out.write("PGCOPY\n".getBytes(StandardCharsets.US_ASCII));
      out.write(0xff);
      out.write('\r');
      out.write('\n');
      out.write(0);
      out.writeInt(0);
      out.writeInt(0);
      for (int offset = 0, index = 0; offset < data.length; index++) {
        int bytes = Math.min(chunk, data.length - offset);
        out.writeShort(4);
        out.writeInt(8);
        out.writeLong(packId);
        out.writeInt(4);
        out.writeInt(mode.equals("duplicate") ? 0 : index);
        out.writeInt(bytes);
        if (mode.equals("truncated") && index == 1) {
          out.write(data, offset, 257);
          return;
        }
        out.write(data, offset, bytes);
        out.writeInt(4);
        out.writeInt(bytes);
        offset += bytes;
      }
      out.writeShort(-1);
    }
    System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
  }
}
