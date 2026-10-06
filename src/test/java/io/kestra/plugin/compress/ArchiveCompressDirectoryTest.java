package io.kestra.plugin.compress;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ArchiveCompressDirectoryTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private CompressUtils compressUtils;

    static Stream<Arguments> source() {
        return Stream.of(
            Arguments.of(ArchiveDecompress.ArchiveAlgorithm.ZIP, null),
            Arguments.of(ArchiveDecompress.ArchiveAlgorithm.JAR, null),
            Arguments.of(ArchiveDecompress.ArchiveAlgorithm.TAR, null),
            Arguments.of(ArchiveDecompress.ArchiveAlgorithm.TAR, ArchiveDecompress.CompressionAlgorithm.GZIP),
            Arguments.of(ArchiveDecompress.ArchiveAlgorithm.CPIO, null)
        );
    }

    @ParameterizedTest
    @MethodSource("source")
    void workingDirectory(ArchiveDecompress.ArchiveAlgorithm algorithm, ArchiveDecompress.CompressionAlgorithm compression) throws Exception {
        ArchiveCompress task = task(algorithm, compression).fromDirectory(Property.ofValue("data")).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Path root = runContext.workingDir().path().resolve("data");
        Files.createDirectories(root.resolve("sub/deeper"));
        Files.createDirectories(root.resolve("empty"));
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("sub/b.txt"), "b");
        Files.writeString(root.resolve("sub/deeper/c.txt"), "c");

        Map<String, String> files = read(task.run(runContext), algorithm, compression, runContext);

        assertThat(files.keySet(), containsInAnyOrder("a.txt", "sub/b.txt", "sub/deeper/c.txt"));
        assertThat(files.get("a.txt"), is("a"));
        assertThat(files.get("sub/b.txt"), is("b"));
        assertThat(files.get("sub/deeper/c.txt"), is("c"));
    }

    @Test
    void emptyDirectoriesAreArchived() throws Exception {
        ArchiveCompress task = task(ArchiveDecompress.ArchiveAlgorithm.ZIP, null).fromDirectory(Property.ofValue("data")).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Path root = runContext.workingDir().path().resolve("data");
        Files.createDirectories(root.resolve("empty/nested"));
        Files.writeString(root.resolve("a.txt"), "a");

        Set<String> entries = entryNames(task.run(runContext), ArchiveDecompress.ArchiveAlgorithm.ZIP, runContext);

        assertThat(entries, containsInAnyOrder("a.txt", "empty/", "empty/nested/"));
    }

    @Test
    void internalStorageDirectory() throws Exception {
        ArchiveCompress probe = task(ArchiveDecompress.ArchiveAlgorithm.TAR, null).fromDirectory(Property.ofValue("unused")).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, probe, Map.of());

        URI uri = runContext.storage().putFile(new ByteArrayInputStream("s".getBytes(StandardCharsets.UTF_8)), "storage-test/dir/with%20space.txt");
        runContext.storage().putFile(new ByteArrayInputStream("n".getBytes(StandardCharsets.UTF_8)), "storage-test/dir/sub/n.txt");

        // the directory URI is the parent of the file URI
        String directory = uri.toString().substring(0, uri.toString().lastIndexOf('/'));
        ArchiveCompress task = task(ArchiveDecompress.ArchiveAlgorithm.TAR, null).fromDirectory(Property.ofValue(directory)).build();

        Map<String, String> files = read(task.run(runContext), ArchiveDecompress.ArchiveAlgorithm.TAR, null, runContext);

        assertThat(files.keySet(), containsInAnyOrder("with space.txt", "sub/n.txt"));
        assertThat(files.get("sub/n.txt"), is("n"));
    }

    @Test
    void fromAndFromDirectoryTogether() throws Exception {
        URI f1 = compressUtils.uploadToStorageString("map");
        ArchiveCompress task = task(ArchiveDecompress.ArchiveAlgorithm.ZIP, null)
            .fromDirectory(Property.ofValue("data"))
            .from(Map.of("extra/map.txt", f1.toString()))
            .build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Path root = runContext.workingDir().path().resolve("data");
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.txt"), "a");

        Map<String, String> files = read(task.run(runContext), ArchiveDecompress.ArchiveAlgorithm.ZIP, null, runContext);

        assertThat(files.keySet(), containsInAnyOrder("a.txt", "extra/map.txt"));
        assertThat(files.get("extra/map.txt"), is("map"));
    }

    @Test
    void requiresFromOrFromDirectory() throws Exception {
        ArchiveCompress task = task(ArchiveDecompress.ArchiveAlgorithm.ZIP, null).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Exception exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("`from` or `fromDirectory`"));
    }

    @Test
    void directoryOutsideWorkingDirectoryIsRejected() throws Exception {
        ArchiveCompress task = task(ArchiveDecompress.ArchiveAlgorithm.ZIP, null).fromDirectory(Property.ofValue("../outside")).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    @Test
    void unsupportedSchemeIsRejected() throws Exception {
        ArchiveCompress task = task(ArchiveDecompress.ArchiveAlgorithm.ZIP, null).fromDirectory(Property.ofValue("http://example.com/dir")).build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        Exception exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("Scheme not supported"));
    }

    private static ArchiveCompress.ArchiveCompressBuilder<?, ?> task(ArchiveDecompress.ArchiveAlgorithm algorithm, ArchiveDecompress.CompressionAlgorithm compression) {
        return ArchiveCompress.builder()
            .id(IdUtils.create())
            .type(ArchiveCompress.class.getName())
            .algorithm(Property.ofValue(algorithm))
            .compression(compression == null ? null : Property.ofValue(compression));
    }

    /** Decompresses the archive with the plugin's own decompressor and returns the file contents by entry name. */
    private Map<String, String> read(ArchiveCompress.Output output, ArchiveDecompress.ArchiveAlgorithm algorithm, ArchiveDecompress.CompressionAlgorithm compression, RunContext runContext)
        throws Exception {
        ArchiveDecompress decompress = ArchiveDecompress.builder()
            .id(IdUtils.create())
            .type(ArchiveDecompress.class.getName())
            .algorithm(Property.ofValue(algorithm))
            .compression(compression == null ? null : Property.ofValue(compression))
            .from(Property.ofValue(output.getUri().toString()))
            .build();

        ArchiveDecompress.Output decompressed = decompress.run(TestsUtils.mockRunContext(runContextFactory, decompress, Map.of()));

        Map<String, String> files = new HashMap<>();
        for (Map.Entry<String, URI> file : decompressed.getFiles().entrySet()) {
            try (InputStream in = runContext.storage().getFile(file.getValue())) {
                files.put(file.getKey(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private Set<String> entryNames(ArchiveCompress.Output output, ArchiveDecompress.ArchiveAlgorithm algorithm, RunContext runContext) throws Exception {
        ArchiveCompress reader = task(algorithm, null).fromDirectory(Property.ofValue("unused")).build();
        Set<String> names = new java.util.HashSet<>();
        try (
            InputStream in = runContext.storage().getFile(output.getUri());
            ArchiveInputStream<?> archive = reader.archiveInputStream(in, runContext)
        ) {
            ArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                names.add(entry.getName());
                IOUtils.consume(archive);
            }
        }
        return names;
    }
}
