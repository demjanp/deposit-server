package deposit.common;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemTest {

	private FileSystem fileSystem;

	@BeforeEach
	void setUp() throws FileSystemException {
		String homeDir = System.getProperty("java.io.tmpdir").toString() + "Deposit_Testing";
		System.setProperty("user.home", homeDir);
		fileSystem = FileSystem.getInstance();
	}

	@Test
	void testSingletonInstance() throws FileSystemException {
		FileSystem instance1 = FileSystem.getInstance();
		FileSystem instance2 = FileSystem.getInstance();
		assertSame(instance1, instance2, "FileSystem should return the same instance");
	}

	@Test
	void testStoreAndRetrieveFile() {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		assertNotNull(generatedFilename, "Generated filename should not be null");

		String retrievedContent = fileSystem.readFromTransient(generatedFilename);
		assertNotNull(retrievedContent, "Retrieved content should not be null");

		String decodedContent = new String(Base64.getDecoder().decode(retrievedContent));
		assertEquals(content, decodedContent, "Retrieved content should match the original content");
	}

	@Test
	void testGetImageFormat() {
		assertEquals("JPEG", FileSystem.getImageFormat("image.jpg"), "Expected format to be JPEG for .jpg extension");
		assertEquals("JPEG", FileSystem.getImageFormat("image.jpeg"), "Expected format to be JPEG for .jpeg extension");
		assertEquals("PNG", FileSystem.getImageFormat("image.png"), "Expected format to be PNG for .png extension");
		assertEquals("GIF", FileSystem.getImageFormat("image.gif"), "Expected format to be GIF for .gif extension");
		assertEquals("SVG", FileSystem.getImageFormat("vector.svg"), "Expected format to be SVG for .svg extension");
		assertEquals("TIFF", FileSystem.getImageFormat("image.tif"), "Expected format to be TIFF for .tif extension");
		assertEquals("TIFF", FileSystem.getImageFormat("image.tiff"), "Expected format to be TIFF for .tiff extension");
		assertEquals("BMP", FileSystem.getImageFormat("image.bmp"), "Expected format to be BMP for .bmp extension");

		assertEquals("", FileSystem.getImageFormat("document.pdf"), "Expected empty string for non-image file extensions");
		assertEquals("", FileSystem.getImageFormat("archive.zip"), "Expected empty string for non-image file extensions");
		assertEquals("", FileSystem.getImageFormat(""), "Expected empty string for empty filename");
		assertEquals("", FileSystem.getImageFormat(null), "Expected empty string for null filename");
	}

	@Test
	void testMoveToPermanentAndDeleteFromPermanent() {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		fileSystem.moveToPermanent(generatedFilename);

		String retrievedContent = fileSystem.readFromPermanent(generatedFilename);
		assertNotNull(retrievedContent, "Content should be retrievable from permanent storage");

		fileSystem.deleteFromPermanent(generatedFilename);

		assertThrows(RuntimeException.class, () -> fileSystem.readFromPermanent(generatedFilename),
					 "Expected RuntimeException when reading a deleted file");
	}

	@Test
	void testDeleteFromArchive() {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		// Store the file in transient storage
		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);

		// Move the file to permanent storage
		fileSystem.moveToPermanent(generatedFilename);

		// Archive the file
		ZonedDateTime timeStamp = ZonedDateTime.now();
		String archivedFilename = fileSystem.moveToArchive(generatedFilename, timeStamp);

		// Verify the file exists in the archive
		String restoredFilename = fileSystem.restoreFromArchive(archivedFilename);
		assertNotNull(restoredFilename, "Restored filename should not be null");

		// Delete the file from archive
		assertDoesNotThrow(() -> fileSystem.deleteFromArchive(archivedFilename),
						   "Deleting the file from the archive should not throw an exception");
	}

	@Test
	void testMoveToArchiveAndRestoreFromArchive() {
		String originalFilename = "testFile.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		fileSystem.moveToPermanent(generatedFilename);

		ZonedDateTime timeStamp = ZonedDateTime.now();
		String archivedFilename = fileSystem.moveToArchive(generatedFilename, timeStamp);

		String restoredFilename = fileSystem.restoreFromArchive(archivedFilename);
		assertNotNull(restoredFilename, "Restored filename should not be null");

		String restoredContent = fileSystem.readFromPermanent(restoredFilename);
		assertNotNull(restoredContent, "Content should be retrievable from permanent storage after restore");

		String decodedContent = new String(Base64.getDecoder().decode(restoredContent));
		assertEquals(content, decodedContent, "Restored content should match the original content");
	}

	@Test
	void testClearTransient() {
		String originalFilename1 = "testFile1.txt";
		String originalFilename2 = "testFile2.txt";
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		fileSystem.storeFile(base64Content, originalFilename1);
		fileSystem.storeFile(base64Content, originalFilename2);

		fileSystem.clearTransient();

		// Expecting RuntimeException due to how ExceptionHandler wraps exceptions
		assertThrows(RuntimeException.class, () -> fileSystem.readFromTransient(originalFilename1));
		assertThrows(RuntimeException.class, () -> fileSystem.readFromTransient(originalFilename2));
	}

	// Edge Cases

	@Test
	void testStoreFileWithEmptyBase64Content() {
		String originalFilename = "emptyFile.txt";
		String emptyBase64Content = "";

		String generatedFilename = fileSystem.storeFile(emptyBase64Content, originalFilename);
		assertNotNull(generatedFilename, "Generated filename should not be null");

		String retrievedContent = fileSystem.readFromTransient(generatedFilename);
		assertEquals("", new String(Base64.getDecoder().decode(retrievedContent)), "Content should be empty");
	}

	@Test
	void testStoreFileWithNullBase64Content() {
		String originalFilename = "nullFile.txt";

		assertThrows(NullPointerException.class, () -> fileSystem.storeFile(null, originalFilename));
	}

	@Test
	void testStoreFileWithNullFilename() {
		String content = "This is a test file.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		assertThrows(NullPointerException.class, () -> fileSystem.storeFile(base64Content, null));
	}

	@Test
	void testFilenameCollisionInTransientStorage() {
		String originalFilename = "collisionFile.txt";
		String content1 = "First content.";
		String content2 = "Second content.";
		String base64Content1 = Base64.getEncoder().encodeToString(content1.getBytes());
		String base64Content2 = Base64.getEncoder().encodeToString(content2.getBytes());

		String generatedFilename1 = fileSystem.storeFile(base64Content1, originalFilename);
		String generatedFilename2 = fileSystem.storeFile(base64Content2, originalFilename);

		assertNotEquals(generatedFilename1, generatedFilename2, "Generated filenames should be unique");

		String retrievedContent1 = fileSystem.readFromTransient(generatedFilename1);
		String retrievedContent2 = fileSystem.readFromTransient(generatedFilename2);

		assertNotEquals(retrievedContent1, retrievedContent2, "Contents should be different for different generated filenames");
	}
	
	@Test
	void testMoveNonExistentFileToPermanentStorage() {
		RuntimeException thrown = assertThrows(RuntimeException.class,
			() -> fileSystem.moveToPermanent("nonexistentFile.txt"),
			"Expected RuntimeException when moving a non-existent file to permanent storage");
		assertTrue(thrown.getMessage().contains("Source file does not exist:"),
			"Expected error message to contain 'Source file does not exist:'");
		assertNull(thrown.getCause(), "Expected cause to be null");
	}
	
	@Test
	void testMoveNonExistentFileToArchiveStorage() {
		ZonedDateTime timeStamp = ZonedDateTime.now();
		RuntimeException thrown = assertThrows(RuntimeException.class,
			() -> fileSystem.moveToArchive("nonexistentFile.txt", timeStamp),
			"Expected RuntimeException when moving a non-existent file to archive storage");
		assertTrue(thrown.getMessage().contains("Source file does not exist:"),
			"Expected error message to contain 'Source file does not exist:'");
		assertNull(thrown.getCause(), "Expected cause to be null");
	}
	
	@Test
	void testRestoreNonExistentArchivedFile() {
		RuntimeException thrown = assertThrows(RuntimeException.class,
			() -> fileSystem.restoreFromArchive("nonexistentArchivedFile.txt"),
			"Expected RuntimeException when restoring a non-existent file from archive");
		assertTrue(thrown.getMessage().contains("Source file does not exist:"),
			"Expected error message to contain 'Source file does not exist:'");
		assertNull(thrown.getCause(), "Expected cause to be null");
	}

	@Test
	void testSpecialCharactersInFilename() {
		String originalFilename = "special!@#$%^&*()_+File.txt";
		String content = "Special characters in the filename.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		assertNotNull(generatedFilename, "Generated filename should not be null");

		String retrievedContent = fileSystem.readFromTransient(generatedFilename);
		assertNotNull(retrievedContent, "Retrieved content should not be null");

		String decodedContent = new String(Base64.getDecoder().decode(retrievedContent));
		assertEquals(content, decodedContent, "Retrieved content should match the original content");
	}

	@Test
	void testHandlingLargeFile() {
		String originalFilename = "largeFile.txt";
		byte[] largeContent = new byte[10 * 1024 * 1024]; // 10 MB of data
		String base64Content = Base64.getEncoder().encodeToString(largeContent);

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		assertNotNull(generatedFilename, "Generated filename should not be null");

		String retrievedContent = fileSystem.readFromTransient(generatedFilename);
		assertNotNull(retrievedContent, "Retrieved content should not be null");

		assertEquals(base64Content, retrievedContent, "Retrieved content should match the original content");
	}

	@Test
	void testLongFilenameHandling() {
		String originalFilename = "long".repeat(50) + ".txt"; // Very long filename
		String content = "This file has a very long filename.";
		String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

		String generatedFilename = fileSystem.storeFile(base64Content, originalFilename);
		assertNotNull(generatedFilename, "Generated filename should not be null");

		String retrievedContent = fileSystem.readFromTransient(generatedFilename);
		assertNotNull(retrievedContent, "Retrieved content should not be null");

		String decodedContent = new String(Base64.getDecoder().decode(retrievedContent));
		assertEquals(content, decodedContent, "Retrieved content should match the original content");
	}
}
