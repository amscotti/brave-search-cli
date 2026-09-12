package io.amscotti.bravesearch.adapter.config;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.util.Iterator;
import java.util.Set;

/**
 * A pinned-directory stream that delegates every operation to the real one, so a test can
 * override exactly the boundary it attacks — for example interposing a filesystem swap on an
 * attribute view or refusing the atomic move.
 */
class DelegatingSecureStream implements SecureDirectoryStream<Path> {

    protected final SecureDirectoryStream<Path> delegate;

    DelegatingSecureStream(SecureDirectoryStream<Path> delegate) {
        this.delegate = delegate;
    }

    @Override
    public SecureDirectoryStream<Path> newDirectoryStream(Path entry, LinkOption... options) throws IOException {
        return delegate.newDirectoryStream(entry, options);
    }

    @Override
    public SeekableByteChannel newByteChannel(
            Path entry, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return delegate.newByteChannel(entry, options, attrs);
    }

    @Override
    public void deleteFile(Path entry) throws IOException {
        delegate.deleteFile(entry);
    }

    @Override
    public void deleteDirectory(Path entry) throws IOException {
        delegate.deleteDirectory(entry);
    }

    @Override
    public void move(Path source, SecureDirectoryStream<Path> targetDirectory, Path target) throws IOException {
        // the real stream only accepts a target bound to its own provider: a self-reference
        // must unwrap to the delegate, or every move through this wrapper fails with a
        // provider mismatch instead of replacing atomically
        delegate.move(source, targetDirectory == this ? delegate : targetDirectory, target);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) {
        return delegate.getFileAttributeView(type);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path entry, Class<V> type, LinkOption... options) {
        return delegate.getFileAttributeView(entry, type, options);
    }

    @Override
    public Iterator<Path> iterator() {
        return delegate.iterator();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
