/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/** Creates deterministic Git data for disposable consumer workload repositories. */
final class ConsumerBenchmarkFixtures {
  private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");
  private static final String MAIN = Constants.R_HEADS + "main";
  private static final String ARTIFACTS = Constants.R_HEADS + "artifacts";
  private static final String PROPOSALS = Constants.R_HEADS + "proposals";
  private static final String PROPOSAL_PATH = "requirements/proposal.json";
  private static final String JAVA_ROOT = "src/main/java/";
  private static final String SANDBOX_PACKAGE = "example.sandbox";
  private static final int PUBLICATION_BATCH = 64;
  private static final String[] REQUIREMENT_PATHS = {
    "requirements/accessibility.json", "requirements/retention.json", "terms/workspace.json"
  };

  private ConsumerBenchmarkFixtures() {}

  /** Live, non-atomic disk gauge that tolerates Lucene removing a segment during a merge. */
  static long liveDirectoryBytes(Path root) throws IOException {
    long[] bytes = {0};
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        if (attributes.isRegularFile()) {
          bytes[0] += attributes.size();
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
        if (failure instanceof NoSuchFileException && !file.equals(root)) {
          return FileVisitResult.CONTINUE;
        }
        throw failure;
      }
    });
    return bytes[0];
  }

  /**
   * Append one binary artifact and one metadata commit to separate branches. Use the same seed and
   * increasing publication numbers to replay a repository's history.
   */
  static AudioPublication publishAudio(
      Repository repository, long seed, int publication, long payloadBytes) throws IOException {
    if (publication < 0 || payloadBytes <= 0) {
      throw new IllegalArgumentException("Expected a non-negative publication and positive size");
    }
    ObjectId oldArtifact = repository.resolve(ARTIFACTS);
    ObjectId oldMetadata = repository.resolve(MAIN);
    if (oldMetadata == null) {
      initializeMainHead(repository);
    }
    PersonIdent identity = identity("Artifact publisher", "audio-publisher", publication);
    try (ObjectInserter inserter = repository.newObjectInserter();
        InputStream stream =
            new RandomPayload(seed * 0x9e3779b97f4a7c15L + publication, payloadBytes)) {
      ObjectId payload = inserter.insert(Constants.OBJ_BLOB, payloadBytes, stream);
      ObjectId artifact =
          commit(
              inserter,
              Map.of("artifact.bin", payload),
              identity,
              identity,
              "Publish synthetic binary artifact " + publication,
              parents(oldArtifact));
      ObjectId manifest =
          text(
              inserter,
              "{\"artifact\":\""
                  + payload.name()
                  + "\",\"bytes\":"
                  + payloadBytes
                  + ",\"publication\":"
                  + publication
                  + "}\n");
      ObjectId metadata =
          commit(
              inserter,
              Map.of("metadata/latest.json", manifest),
              identity,
              identity,
              "Record analysis result " + publication,
              parents(oldMetadata));
      inserter.flush();
      publishAudioRefs(
          repository,
          identity,
          List.of(
              new ReceiveCommand(
                  Objects.requireNonNullElse(oldArtifact, ObjectId.zeroId()), artifact, ARTIFACTS),
              new ReceiveCommand(
                  Objects.requireNonNullElse(oldMetadata, ObjectId.zeroId()), metadata, MAIN)));
      return new AudioPublication(metadata, artifact, payload);
    }
  }

  private static void publishAudioRefs(
      Repository repository, PersonIdent identity, List<ReceiveCommand> commands)
      throws IOException {
    BatchRefUpdate update = repository.getRefDatabase().newBatchUpdate();
    update.setAtomic(true);
    update.setRefLogIdent(identity);
    update.setRefLogMessage("publish audio fixture", false);
    update.setForceRefLog(true);
    update.addCommand(commands);
    try (RevWalk walk = new RevWalk(repository)) {
      update.execute(walk, NullProgressMonitor.INSTANCE);
    }
    for (ReceiveCommand command : commands) {
      if (command.getResult() != ReceiveCommand.Result.OK) {
        throw new IOException(
            "Could not publish audio fixture ref "
                + command.getRefName()
                + ": "
                + command.getResult());
      }
    }
  }

  /** Create one workspace with exactly the requested number of commits reachable from main. */
  static ObjectId createTaxonomyHistory(Repository repository, int workspace, int commits)
      throws IOException {
    requireFreshHistory(repository, workspace, commits);
    if (repository.exactRef(PROPOSALS) != null) {
      throw new IllegalArgumentException("Fixture needs an unused proposals ref");
    }
    initializeMainHead(repository);
    Map<String, ObjectId> files = new TreeMap<>();
    List<RefChange> changes = new ArrayList<>(PUBLICATION_BATCH);
    ObjectId main = null;
    ObjectId proposal = null;
    ObjectId pendingProposal = null;
    ObjectId pendingProposalBlob = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int revision = 0; revision < commits; revision++) {
        int step = revision % 8;
        int authorNumber = revision % 4;
        PersonIdent author =
            identity(
                "Workspace author " + authorNumber, "workspace-author-" + authorNumber, revision);
        if (step == 2 && revision < commits - 2) {
          Map<String, ObjectId> branchFiles = new TreeMap<>(files);
          pendingProposalBlob =
              text(inserter, requirement(workspace, "proposal", "proposed", revision));
          branchFiles.put(PROPOSAL_PATH, pendingProposalBlob);
          pendingProposal =
              commit(
                  inserter, branchFiles, author, author, "Propose taxonomy change", parents(main));
          changes.add(
              new RefChange(PROPOSALS, proposal, pendingProposal, author, "submit proposal"));
          proposal = pendingProposal;
        } else {
          int slot = revision % REQUIREMENT_PATHS.length;
          String title = slot == 0 ? "accessibility baseline" : "workspace terminology";
          files.put(
              REQUIREMENT_PATHS[slot],
              text(
                  inserter,
                  requirement(workspace, title, revision % 2 == 0 ? "active" : "draft", revision)));
          boolean merge = step == 4 && pendingProposal != null;
          PersonIdent committer =
              merge ? identity("Workspace reviewer", "reviewer", revision) : author;
          if (merge) {
            files.put(PROPOSAL_PATH, pendingProposalBlob);
          }
          String message = merge ? "Merge proposal into workspace" : "Update " + title;
          List<ObjectId> parents = merge ? List.of(main, pendingProposal) : parents(main);
          ObjectId next = commit(inserter, files, author, committer, message, parents);
          changes.add(new RefChange(MAIN, main, next, committer, message));
          main = next;
          if (merge) {
            pendingProposal = null;
            pendingProposalBlob = null;
          }
        }
        if (changes.size() == PUBLICATION_BATCH) {
          publish(inserter, repository, changes);
        }
      }
      publish(inserter, repository, changes);
    }
    return main;
  }

  /**
   * Create a self-contained Java source tree. After the initial commit, repeat method rename, class
   * rename, package move and method-body edit, including updates to the caller when required.
   */
  static ObjectId createSandboxHistory(
      Repository repository, int workspace, int classes, int commits) throws IOException {
    requireFreshHistory(repository, workspace, commits);
    if (classes <= 0) {
      throw new IllegalArgumentException("Expected at least one Java class");
    }
    initializeMainHead(repository);
    Map<String, ObjectId> files = new TreeMap<>();
    List<RefChange> changes = new ArrayList<>(PUBLICATION_BATCH);
    ObjectId main = null;
    String method = "getValue";
    String className = "CleanupTask0";
    String packageName = SANDBOX_PACKAGE;
    String previousPath = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 1; index < classes; index++) {
        String stableClass = "CleanupTask" + index;
        files.put(
            javaPath(SANDBOX_PACKAGE, stableClass),
            text(inserter, javaClass(SANDBOX_PACKAGE, stableClass, "getValue", workspace ^ index)));
      }
      for (int revision = 0; revision < commits; revision++) {
        String message;
        switch (revision % 4) {
          case 1 -> {
            method = method.equals("getValue") ? "value" : "getValue";
            message = "Rename cleanup method to " + method;
          }
          case 2 -> {
            className = className.equals("CleanupTask0") ? "RefactoredTask0" : "CleanupTask0";
            message = "Rename cleanup class to " + className;
          }
          case 3 -> {
            packageName =
                packageName.equals(SANDBOX_PACKAGE)
                    ? SANDBOX_PACKAGE + ".cleaned"
                    : SANDBOX_PACKAGE;
            message = "Move cleanup class to " + packageName;
          }
          default ->
              message = revision == 0 ? "Initialize Java cleanup tree" : "Edit cleanup method body";
        }
        String path = javaPath(packageName, className);
        if (previousPath != null && !previousPath.equals(path)) {
          files.remove(previousPath);
        }
        int value = workspace ^ (revision / 4);
        files.put(path, text(inserter, javaClass(packageName, className, method, value)));
        if (revision == 0 || revision % 4 != 0) {
          files.put(
              javaPath(SANDBOX_PACKAGE, "CleanupClient"),
              text(inserter, javaCaller(packageName, className, method)));
        }
        previousPath = path;
        PersonIdent identity = identity("Java cleanup author", "cleanup-author", revision);
        ObjectId next = commit(inserter, files, identity, identity, message, parents(main));
        changes.add(new RefChange(MAIN, main, next, identity, message));
        main = next;
        if (changes.size() == PUBLICATION_BATCH) {
          publish(inserter, repository, changes);
        }
      }
      publish(inserter, repository, changes);
    }
    return main;
  }

  private static void requireFreshHistory(Repository repository, int workspace, int commits)
      throws IOException {
    if (workspace < 0 || commits <= 0 || repository.exactRef(MAIN) != null) {
      throw new IllegalArgumentException(
          "Expected a non-negative workspace, positive commit count and unused main ref");
    }
  }

  private static void initializeMainHead(Repository repository) throws IOException {
    Ref head = repository.exactRef(Constants.HEAD);
    if (head != null) {
      if (head.isSymbolic() && MAIN.equals(head.getTarget().getName())) {
        return;
      }
      if (head.getObjectId() != null) {
        throw new IllegalArgumentException("Fixture needs an unborn HEAD or HEAD at main");
      }
    }
    RefUpdate update = repository.updateRef(Constants.HEAD, true);
    update.disableRefLog();
    RefUpdate.Result result = update.link(MAIN);
    if (result != RefUpdate.Result.NEW
        && result != RefUpdate.Result.FORCED
        && result != RefUpdate.Result.NO_CHANGE) {
      throw new IOException("Could not link fixture HEAD to " + MAIN + ": " + result);
    }
  }

  private static String requirement(int workspace, String title, String status, int revision) {
    return """
        {"workspace":%s,"title":"%s","status":"%s","revision":%s}
        """.formatted(workspace, title, status, revision);
  }

  private static String javaPath(String packageName, String className) {
    return JAVA_ROOT + packageName.replace('.', '/') + "/" + className + ".java";
  }

  private static String javaClass(String packageName, String className, String method, int value) {
    return """
        package %s;
        public final class %s {
          public static int %s() { return %s; }
        }
        """.formatted(packageName, className, method, value);
  }

  private static String javaCaller(String packageName, String className, String method) {
    return """
        package %s;
        public final class CleanupClient {
          public int run() { return %s.%s.%s(); }
        }
        """.formatted(SANDBOX_PACKAGE, packageName, className, method);
  }

  private static ObjectId text(ObjectInserter inserter, String text) throws IOException {
    return inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
  }

  private static PersonIdent identity(String name, String localPart, int revision) {
    return new PersonIdent(
        name, localPart + "@example.invalid", BASE_TIME.plusSeconds(revision), ZoneOffset.UTC);
  }

  private static List<ObjectId> parents(ObjectId parent) {
    return parent == null ? List.of() : List.of(parent);
  }

  private static ObjectId commit(
      ObjectInserter inserter,
      Map<String, ObjectId> files,
      PersonIdent author,
      PersonIdent committer,
      String message,
      List<ObjectId> parents)
      throws IOException {
    DirCache tree = DirCache.newInCore();
    var builder = tree.builder();
    for (var file : files.entrySet()) {
      DirCacheEntry entry = new DirCacheEntry(file.getKey());
      entry.setFileMode(FileMode.REGULAR_FILE);
      entry.setObjectId(file.getValue());
      builder.add(entry);
    }
    builder.finish();
    CommitBuilder commit = new CommitBuilder();
    commit.setTreeId(tree.writeTree(inserter));
    commit.setParentIds(parents);
    commit.setAuthor(author);
    commit.setCommitter(committer);
    commit.setMessage(message);
    return inserter.insert(commit);
  }

  private static void publish(
      ObjectInserter inserter, Repository repository, List<RefChange> changes) throws IOException {
    if (!changes.isEmpty()) {
      inserter.flush();
      for (RefChange change : changes) {
        change.apply(repository);
      }
      changes.clear();
    }
  }

  record AudioPublication(ObjectId metadataCommit, ObjectId artifactCommit, ObjectId payload) {}

  private record RefChange(
      String name, ObjectId previous, ObjectId next, PersonIdent identity, String message) {
    void apply(Repository repository) throws IOException {
      RefUpdate update = repository.updateRef(name);
      update.setExpectedOldObjectId(previous == null ? ObjectId.zeroId() : previous);
      update.setNewObjectId(next);
      update.setRefLogIdent(identity);
      update.setRefLogMessage(message, false);
      update.setForceRefLog(true);
      RefUpdate.Result result = update.update();
      RefUpdate.Result expected =
          previous == null ? RefUpdate.Result.NEW : RefUpdate.Result.FAST_FORWARD;
      if (result != expected) {
        throw new IOException("Could not publish fixture ref " + name + ": " + result);
      }
    }
  }

  /** A size-bounded stream whose bytes do not depend on the caller's read sizes. */
  private static final class RandomPayload extends InputStream {
    private final Random random;
    private final byte[] block = new byte[64 * 1024];
    private int position = block.length;
    private long remaining;

    private RandomPayload(long seed, long length) {
      random = new Random(seed);
      remaining = length;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      refill();
      remaining--;
      return Byte.toUnsignedInt(block[position++]);
    }

    @Override
    public int read(byte[] bytes, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) {
        return 0;
      }
      if (remaining == 0) {
        return -1;
      }
      refill();
      int copied = (int) Math.min(remaining, Math.min(length, block.length - position));
      System.arraycopy(block, position, bytes, offset, copied);
      position += copied;
      remaining -= copied;
      return copied;
    }

    private void refill() {
      if (position == block.length) {
        random.nextBytes(block);
        position = 0;
      }
    }
  }
}
