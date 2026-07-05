package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
public class HibernateRepositoryBenchmark {
  private final AtomicInteger counter = new AtomicInteger();
  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private ObjectId blobId;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    String repositoryName = "jmh-h2-" + Long.toHexString(System.nanoTime());
    provider = new HibernateSessionFactoryProvider(h2Properties(repositoryName));
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    blobId = writeBlob("initial");
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (repository != null) {
      repository.close();
    }
    if (provider != null) {
      provider.close();
    }
  }

  @Benchmark
  public ObjectId writeBlob() throws Exception {
    return writeBlob("payload-" + counter.incrementAndGet());
  }

  @Benchmark
  public byte[] readBlob() throws Exception {
    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader loader = reader.open(blobId);
      return loader.getBytes();
    }
  }

  private ObjectId writeBlob(String content) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId id = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      inserter.flush();
      return id;
    }
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
