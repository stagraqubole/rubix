/**
 * Copyright (c) 2019. Qubole Inc
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package com.qubole.rubix.tests;

import com.codahale.metrics.MetricRegistry;
import com.qubole.rubix.bookkeeper.BookKeeperServer;
import com.qubole.rubix.bookkeeper.LocalDataTransferServer;
import com.qubole.rubix.bookkeeper.utils.DiskUtils;
import com.qubole.rubix.common.utils.DataGen;
import com.qubole.rubix.common.utils.DeleteFileVisitor;
import com.qubole.rubix.core.CachingFileSystemStats;
import com.qubole.rubix.core.CachingInputStream;
import com.qubole.rubix.core.MockCachingFileSystem;
import com.qubole.rubix.core.NonLocalReadRequestChain;
import com.qubole.rubix.core.ReadRequest;
import com.qubole.rubix.spi.BookKeeperFactory;
import com.qubole.rubix.spi.CacheConfig;
import com.qubole.rubix.spi.CacheUtil;
import com.qubole.rubix.spi.ClusterType;
import com.qubole.rubix.spi.RetryingPooledBookkeeperClient;
import com.qubole.rubix.spi.thrift.BlockLocation;
import com.qubole.rubix.spi.thrift.CacheStatusRequest;
import com.qubole.rubix.spi.thrift.CacheStatusResponse;
import com.qubole.rubix.spi.thrift.FileInfo;
import com.qubole.rubix.spi.thrift.Location;
import com.qubole.rubix.spi.thrift.ReadResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.thrift.shaded.TException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.qubole.rubix.spi.CacheUtil.UNKONWN_GENERATION_NUMBER;
import static org.testng.Assert.assertTrue;

public class TestGenerationNumber
{
  private static final String testDirectoryPrefix = System.getProperty("java.io.tmpdir") + "/tmp/testPath";
  private static final long TEST_LAST_MODIFIED = 1514764800; // 2018-01-01T00:00:00
  private static final long TEST_FILE_LENGTH = 5000;
  private static final String testDirectory = testDirectoryPrefix + "dir0";

  private static final Log log = LogFactory.getLog(TestGenerationNumber.class);

  int blockSize = 100;
  String backendFileName = testDirectoryPrefix + "backendFile";
  File backendFile;
  Path backendPath = new Path("file:///" + backendFileName.substring(1));

  private BookKeeperServer bookKeeperServer;
  private BookKeeperFactory bookKeeperFactory;

  MockCachingFileSystem fs;
  CachingInputStream inputStream;
  final Configuration conf = new Configuration();

  @BeforeClass
  public static void setupClass()
      throws IOException
  {
    log.info(testDirectory);
    Files.createDirectories(Paths.get(testDirectory));
  }

  @AfterClass
  public static void tearDownClass()
      throws IOException
  {
    Files.walkFileTree(Paths.get(testDirectory), new DeleteFileVisitor());
    Files.deleteIfExists(Paths.get(testDirectory));
  }

  @BeforeMethod
  public void setup()
      throws Exception
  {
    CacheConfig.setOnMaster(conf, true);
    CacheConfig.setIsStrictMode(conf, true);
    CacheConfig.setBookKeeperServerPort(conf, 3456);
    CacheConfig.setDataTransferServerPort(conf, 2222);
    CacheConfig.setBlockSize(conf, blockSize);
    CacheConfig.setCacheDataDirPrefix(conf, testDirectoryPrefix + "dir");
    CacheConfig.setMaxDisks(conf, 1);
    CacheConfig.setIsParallelWarmupEnabled(conf, false);
    CacheConfig.setCleanupFilesDuringStart(conf, false);
    bookKeeperFactory = new BookKeeperFactory();
    startServer();
    fs = new MockCachingFileSystem();
    fs.initialize(backendPath.toUri(), conf);
    // Populate File
    DataGen.populateFile(backendFileName);
    backendFile = new File(backendFileName);
    inputStream = createCachingStream(conf);
    log.info("BackendPath: " + backendPath);
  }

  private CachingInputStream createCachingStream(Configuration conf)
      throws IOException
  {
    FileSystem localFileSystem = new RawLocalFileSystem();
    Path backendFilePath = new Path(backendFileName);
    localFileSystem.initialize(backendFilePath.toUri(), new Configuration());
    CacheConfig.setBlockSize(conf, blockSize);

    // This should be after server comes up else client could not be created
    return new CachingInputStream(backendPath, conf,
        new CachingFileSystemStats(), ClusterType.TEST_CLUSTER_MANAGER,
        new BookKeeperFactory(), localFileSystem,
        CacheConfig.getBlockSize(conf), null);
  }

  /**
   * Verify that correct data is read when invalidation is triggered simultaneously
   * with parallel warmup disabled.
   */
  @Test
  void testReadWithInvalidationsWithoutParalleWarmUp()
      throws Exception
  {
    testReadWithInvalidationHelper();
  }

  /**
   * Verify that correct data is read when invalidation is triggered simultaneously
   * with parallel warmup enabled.
   */
  @Test
  void testReadWithInvalidationsWithParallelWarmUp()
      throws Exception
  {
    CacheConfig.setIsParallelWarmupEnabled(conf, true);
    CacheConfig.setRemoteFetchProcessInterval(conf, 500);
    restartServer();
    testReadWithInvalidationHelper();
  }

  void testReadWithInvalidationHelper()
      throws Exception
  {
    int maxReadTasks  = 200;
    final RetryingPooledBookkeeperClient client = getBookKeeperClient();
    Thread invalidateRequest = new Thread(new Runnable() {
      @Override
      public void run() {
        for (int i = 1; i < 100; i++)
        {
          try {
            client.invalidateFileMetadata(backendPath.toString());
            Thread.sleep(500);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    });

    ExecutorService service = Executors.newFixedThreadPool(20);
    List<Future> readtasks = new ArrayList<>();
    // submit the read task for execution
    for(int id = 0; id < maxReadTasks; id++)
    {
      int readLength = new Random().nextInt(2000);
      readtasks.add(service.submit(new ReadTask(readLength, createCachingStream(conf))));
    }
    invalidateRequest.start();
      for (int id = 0; id < maxReadTasks; id++) {
        Future<Data> result = readtasks.get(id);
        Data data = result.get();
        assertions(data.getReadSize(), data.getexpectedReadSize(), data.getBuffer());
      }
      invalidateRequest.join();
  }

  class ReadTask implements Callable<Data>
  {
    CachingInputStream inputStream;
    int readLength;
    ReadTask(int readLength, CachingInputStream inputStream)
    {
      this.readLength = readLength;
      this.inputStream = inputStream;
    }
    @Override
    public Data call()
    {
      try {
        byte[] buffer = new byte[readLength];
        inputStream.seek(0);
        int readSize = inputStream.read(buffer, 0, readLength);
        Thread.sleep(2000);
        return new Data(buffer, readSize, readLength);
      }
      catch (Exception e)
      {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Verify that correct data is read via non local RRC when invalidation is triggered simultaneously
   * with parallel warmup disabled.
   */
  @Test
  void testNonLocalReadWithInvalidationsWithoutParalleWarmUp()
      throws Exception
  {
    testNonLocalReadWithInvalidationHelper();
  }

  /**
   * Verify that correct data is read via non local RRC when invalidation is triggered simultaneously
   * with parallel warmup enabled.
   */
  @Test
  void testNonLocalReadWithInvalidationsWithParallelWarmUp()
      throws Exception
  {
    CacheConfig.setIsParallelWarmupEnabled(conf, true);
    CacheConfig.setRemoteFetchProcessInterval(conf, 500);
    restartServer();
    testNonLocalReadWithInvalidationHelper();
  }

  void testNonLocalReadWithInvalidationHelper()
      throws Exception
  {
    Thread localDataTransferServer;
    localDataTransferServer = new Thread()
    {
      public void run()
      {
        LocalDataTransferServer.startServer(conf, new MetricRegistry());
      }
    };
    localDataTransferServer.start();
    while (!LocalDataTransferServer.isServerUp()) {
      Thread.sleep(200);
      log.info("Waiting for Local Data Transfer Server to come up");
    }

    int maxNonLocalReadTasks  = 100;
    final RetryingPooledBookkeeperClient client = getBookKeeperClient();
    Thread invalidateRequest = new Thread(new Runnable() {
      @Override
      public void run() {
        for (int i = 1; i < 50; i++)
        {
          try {
            client.invalidateFileMetadata(backendPath.toString());
            Thread.sleep(500);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    });
    ExecutorService service = Executors.newFixedThreadPool(20);
    List<Future> NonLocalreadtasks = new ArrayList<>();

    // submit the read task for execution
    for(int id = 0; id < maxNonLocalReadTasks; id++)
    {
      int readLength = new Random().nextInt(100);
      NonLocalreadtasks.add(service.submit(new NonLocalReadTask(createNewNonLocalReadRequest(), readLength)));
    }
    invalidateRequest.start();
    for (int id = 0; id < maxNonLocalReadTasks; id++) {
      Future<Data> result = NonLocalreadtasks.get(id);
      Data data = result.get();
      assertions(data.getReadSize(), data.getexpectedReadSize(), data.getBuffer());
      }
      invalidateRequest.join();
  }

  private NonLocalReadRequestChain createNewNonLocalReadRequest()
      throws IOException
  {
    NonLocalReadRequestChain requestChain = new NonLocalReadRequestChain("localhost", backendFile.length(),
        backendFile.lastModified(), conf, fs.getRemoteFileSystem(), backendPath.toString(),
        ClusterType.TEST_CLUSTER_MANAGER.ordinal(), false, null);
    return requestChain;
  }

  class NonLocalReadTask implements Callable<Data>
  {
    NonLocalReadRequestChain requestChain;
    int readLength;
    NonLocalReadTask(NonLocalReadRequestChain requestChain, int readLength)
    {
      this.requestChain = requestChain;
      this.readLength = readLength;
    }
    @Override
    public Data call() throws Exception
    {
      byte[] buffer = new byte[readLength];
      ReadRequest readRequest = new ReadRequest(0, 100, 0, readLength, buffer, 0, backendFile.length());
      requestChain.addReadRequest(readRequest);
      requestChain.lock();
      int readSize = Math.toIntExact(requestChain.call());
      Thread.sleep(2000);
      return new Data(buffer, readSize, readLength);
    }
  }

  class Data
  {
    byte[] buffer;
    int readSize;
    int expectedReadSize;
    public Data(byte[] buffer, int readSize, int expectedReadSize)
    {
      this.buffer = buffer;
      this.readSize = readSize;
      this.expectedReadSize = expectedReadSize;
    }

    public byte[] getBuffer()
    {
      return buffer;
    }

    public int getReadSize()
    {
      return readSize;
    }

    public int getexpectedReadSize()
    {
      return expectedReadSize;
    }
  }

  /**
   * Verify that generation number get incremented after each invalidation call
   */
  @Test
  void testGenerationNumberIncrement()
      throws TException, IOException
  {
    RetryingPooledBookkeeperClient client = getBookKeeperClient();
    final int readOffset = 0, readLength = 2000;
    client.readData(backendPath.toString(), readOffset, readLength, TEST_FILE_LENGTH, TEST_LAST_MODIFIED, ClusterType.TEST_CLUSTER_MANAGER.ordinal());
    client.invalidateFileMetadata(backendPath.toString());
    CacheStatusRequest request = new CacheStatusRequest(backendPath.toString(),
        TEST_FILE_LENGTH,
        TEST_LAST_MODIFIED,
        0,
        20).setClusterType(ClusterType.TEST_CLUSTER_MANAGER.ordinal());
    int generationNumber = client.getCacheStatus(request).getGenerationNumber();
    assertTrue(generationNumber == UNKONWN_GENERATION_NUMBER + 2,
        "generation number is reported: " + generationNumber +
        "but expected: " + (UNKONWN_GENERATION_NUMBER + 2));
  }

  /**
   * Verify that correct generation number is returned during startup of BKS
   */
  @Test
  void testGenerationNumberDuringStartUp()
      throws Exception
  {
    int generationNumber = 5;
    // create files till generation Number = 5
    creatLocalFilesOnCache(generationNumber);
    testGenerationNumberDuringStartUpHelper(generationNumber, false);
    restartServer();
    creatLocalFilesOnCache(generationNumber);
    new File(CacheUtil.getMetadataFilePath(backendPath.toString(), conf, generationNumber)).delete();
    testGenerationNumberDuringStartUpHelper(generationNumber, true);
    // clean up required during startup
    CacheConfig.setCleanupFilesDuringStart(conf, true);
    restartServer();
    creatLocalFilesOnCache(generationNumber);
    testGenerationNumberDuringStartUpHelper(generationNumber, true);
  }

  void testGenerationNumberDuringStartUpHelper(int generationNumber, boolean misssingFileOrCleanUpRequired)
      throws TException
  {
    RetryingPooledBookkeeperClient client = getBookKeeperClient();
    CacheStatusRequest request = new CacheStatusRequest(backendPath.toString(),
        TEST_FILE_LENGTH,
        TEST_LAST_MODIFIED,
        0,
        20).setClusterType(ClusterType.TEST_CLUSTER_MANAGER.ordinal());
    CacheStatusResponse response = client.getCacheStatus(request);
    if (!misssingFileOrCleanUpRequired)
    {
      // Both datafile and mdfile exists with generationNumber on disk so we should get generationNumber in response
      assertTrue(response.getGenerationNumber() == generationNumber,
          "generation Number is reported:" + response.getGenerationNumber() +
              "but expected: " + generationNumber);
    }
    else
    {
      // datafile exists with generationNumber on disk but without mdFile or cleanup required
      // we should get generationNumber + 1 in response
      assertTrue(response.getGenerationNumber() == generationNumber + 1,
          "generation number is reported: " + response.getGenerationNumber() +
              " but expected: " + (generationNumber + 1) );
    }
  }

  /**
   * Verify that correct generation number is returned after cache warmup
   */
  @Test
  void testGenerationNumberAfterCacheWarmUp() throws Exception {
    int generationNumber = 5;
    RetryingPooledBookkeeperClient client = getBookKeeperClient();
    CacheStatusRequest request = new CacheStatusRequest(backendPath.toString(),
        TEST_FILE_LENGTH,
        TEST_LAST_MODIFIED,
        0,
        20).setClusterType(ClusterType.TEST_CLUSTER_MANAGER.ordinal());
    // warm up the cache
    client.getCacheStatus(request);
    // invalidate the remote path
    client.invalidateFileMetadata(backendPath.toString());
    // create files till generation Number = 5
    creatLocalFilesOnCache(generationNumber);
    CacheStatusResponse response = client.getCacheStatus(request);
    assertTrue(response.getGenerationNumber() == generationNumber + 1,
        "generation Number is reported:" + response.getGenerationNumber() +
            "but expected: " + (generationNumber + 1));
  }

  RetryingPooledBookkeeperClient getBookKeeperClient()
      throws TException
  {
    RetryingPooledBookkeeperClient client = bookKeeperFactory.createBookKeeperClient("localhost", conf);
    return client;
  }

  void creatLocalFilesOnCache(int generationNumber)
      throws IOException
  {
    File mdFile = null, localFile = null;
    for(int genNumber = 1; genNumber <= generationNumber; genNumber++)
    {
      mdFile = new File(CacheUtil.getMetadataFilePath(backendPath.toString(), conf, genNumber));
      mdFile.createNewFile();
      localFile = new File(CacheUtil.getLocalPath(backendPath.toString(), conf, genNumber));
      localFile.createNewFile();
    }
  }

  @AfterMethod
  public void cleanup()
      throws IOException
  {
    // make sure directory is empty before running test
    try {
      int numDisks = CacheConfig.getCacheMaxDisks(conf);
      String dirSuffix = CacheConfig.getCacheDataDirSuffix(conf);
      List<String> dirPrefixList = CacheUtil.getDirPrefixList(conf);

      for (String dirPrefix : dirPrefixList) {
        for (int i = 0; i < numDisks; i++) {
          java.nio.file.Path path = Paths.get(dirPrefix + i, dirSuffix, "*");
          DiskUtils.clearDirectory(path.toString());
        }

        java.nio.file.Path path = Paths.get(dirPrefix, dirSuffix, "*");
        DiskUtils.clearDirectory(path.toString());
      }
    }
    catch (IOException ex) {
      log.error("Could not clean up the old cached files", ex);
    }
    stopServer();
    conf.clear();
  }

  private void stopServer()
  {
    if (bookKeeperServer != null)
    {
      bookKeeperServer.stopServer();
    }
    LocalDataTransferServer.stopServer();
  }

  private void startServer()
      throws InterruptedException
  {
    bookKeeperServer = new BookKeeperServer();
    Thread thread = new Thread()
    {
      public void run()
      {
        bookKeeperServer.startServer(conf, new MetricRegistry());
      }
    };
    thread.start();
    while (!bookKeeperServer.isServerUp())
    {
      Thread.sleep(200);
      log.info("Waiting for BookKeeper Server to come up");
    }
  }

  private void restartServer()
      throws InterruptedException
  {
    stopServer();
    startServer();
  }

  private void assertions(int readSize, int expectedReadSize, byte[] outputBuffer)
  {
    String expectedOutput = DataGen.generateContent().substring(0, expectedReadSize);
    assertTrue(readSize == expectedReadSize, "Wrong amount of data read " + readSize + " was expecting " + expectedReadSize);
    String output = new String(outputBuffer, Charset.defaultCharset());
    assertTrue(expectedOutput.equals(output), "Wrong data read, expected\n" + expectedOutput + "\nBut got\n" + output);
  }
}
