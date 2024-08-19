package deposit.common;

import org.apache.commons.vfs2.FileSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileSystemConcurrencyTest {

	private FileSystem fileSystem;

	@TempDir
	Path tempDir; // Temporary directory for test storage

	@BeforeEach
	void setUp(@TempDir Path tempDir) throws FileSystemException {
		// Use a separate temporary directory for each test
		System.setProperty("user.home", tempDir.toString());
		fileSystem = FileSystem.getInstance();
	}

	@Test
	void testConcurrentReads() throws InterruptedException {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);

		int threadCount = 10;
		CountDownLatch latch = new CountDownLatch(threadCount);
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				assertDoesNotThrow(() -> {
					String retrievedContent = fileSystem.readFromTransient(generatedFilename);
					String decodedContent = new String(Base64.getDecoder().decode(retrievedContent));
					assertEquals(content, decodedContent);
				});
				latch.countDown();
			});
		}

		latch.await(10, TimeUnit.SECONDS);
		executorService.shutdown();
	}

	@Test
	void testConcurrentWrites() throws InterruptedException {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		int threadCount = 10;
		CountDownLatch latch = new CountDownLatch(threadCount);
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {
			int threadId = i;
			executorService.submit(() -> {
				assertDoesNotThrow(() -> {
					String threadSpecificContent = content + " from thread " + threadId;
					String threadSpecificBase64 = Base64.getEncoder().encodeToString(threadSpecificContent.getBytes());
					fileSystem.storeFile(threadSpecificBase64, originalFilename);
				});
				latch.countDown();
			});
		}

		latch.await(10, TimeUnit.SECONDS);
		executorService.shutdown();
	}

	@Test
	void testConcurrentMoves() throws InterruptedException {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);

		int threadCount = 10;
		CountDownLatch latch = new CountDownLatch(threadCount);
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				assertDoesNotThrow(() -> {
					fileSystem.moveToPermanent(generatedFilename);
				});
				latch.countDown();
			});
		}

		latch.await(10, TimeUnit.SECONDS);
		executorService.shutdown();
	}

	@Test
	void testMixedAccess() throws InterruptedException {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);

		int threadCount = 15;
		CountDownLatch latch = new CountDownLatch(threadCount);
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

		for (int i = 0; i < threadCount; i++) {
			int threadId = i;
			executorService.submit(() -> {
				if (threadId % 3 == 0) {
					assertDoesNotThrow(() -> {
						fileSystem.moveToPermanent(generatedFilename);
					});
				} else if (threadId % 3 == 1) {
					assertDoesNotThrow(() -> {
						String retrievedContent = fileSystem.readFromTransient(generatedFilename);
						if (retrievedContent != null) {
							String decodedContent = new String(Base64.getDecoder().decode(retrievedContent));
							assertEquals(content, decodedContent);
						}
					});
				} else {
					assertDoesNotThrow(() -> {
						String threadSpecificContent = content + " from thread " + threadId;
						String threadSpecificBase64 = Base64.getEncoder().encodeToString(threadSpecificContent.getBytes());
						fileSystem.storeFile(threadSpecificBase64, originalFilename);
					});
				}
				latch.countDown();
			});
		}

		latch.await(10, TimeUnit.SECONDS);
		executorService.shutdown();
	}
}
