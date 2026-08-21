#!/usr/bin/env python3
"""Make the retained-evidence credential scan tolerate arbitrary bytes."""

from pathlib import Path


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {description} match, found {count}")
    return text.replace(old, new)


path = Path(
    "jgit-storage-hibernate-benchmarks/src/test/java/"
    "io/github/carstenartur/jgit/storage/hibernate/benchmark/"
    "PackStorageLayoutBenchmarkTest.java"
)
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''import java.io.OutputStream;
import java.nio.file.Files;
''',
    '''import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
''',
    "StandardCharsets import",
)
text = replace_once(
    text,
    '''import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
''',
    '''import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.profile.GCProfiler;
''',
    "TempDir import",
)
text = replace_once(
    text,
    '''class PackStorageLayoutBenchmarkTest {

  private static final String ENABLED_PROPERTY =
''',
    '''class PackStorageLayoutBenchmarkTest {

  @TempDir Path temporaryDirectory;

  private static final String ENABLED_PROPERTY =
''',
    "temporary directory field",
)
text = replace_once(
    text,
    '''  @Test
  void fullAndCapacityProfilesDoNotRepeatBenchmarkCoordinates() {
''',
    '''  @Test
  void credentialScanToleratesNonUtf8Artifacts() throws Exception {
    Path dumpStream = temporaryDirectory.resolve("surefire.dumpstream");
    Files.write(
        dumpStream,
        new byte[] {(byte) 0xc3, (byte) 0x28, 0x00, (byte) 0xff});

    assertCredentialFreeEvidence(temporaryDirectory);
  }

  @Test
  void fullAndCapacityProfilesDoNotRepeatBenchmarkCoordinates() {
''',
    "binary-artifact regression test",
)
text = replace_once(
    text,
    '''        String content = Files.readString(file);
''',
    '''        String content =
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
''',
    "binary-safe evidence decoding",
)
path.write_text(text, encoding="utf-8")
