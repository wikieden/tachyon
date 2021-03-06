package tachyon.client;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;

import org.apache.log4j.Logger;

import tachyon.CommonUtils;
import tachyon.Constants;
import tachyon.UnderFileSystem;
import tachyon.conf.UserConf;

/**
 * <code>OutputStream</code> interface implementation of TachyonFile. It can only be gotten by
 * calling the methods in <code>tachyon.client.TachyonFile</code>, but can not be initialized by
 * the client code.
 */
public class OutStream extends OutputStream {
  private final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);
  private final UserConf USER_CONF = UserConf.get();

  private final TachyonFS TFS;
  private final int FID;
  private final OpType IO_TYPE;

  private long mSizeBytes;
  private ByteBuffer mBuffer;

  private RandomAccessFile mLocalFile;
  private FileChannel mLocalFileChannel;

  private OutputStream mCheckpointOutputStream;

  private boolean mClosed = false;
  private boolean mCancel = false;

  OutStream(TachyonFile file, OpType opType) throws IOException {
    TFS = file.TFS;
    FID = file.FID;
    IO_TYPE = opType;

    mBuffer = ByteBuffer.allocate(USER_CONF.FILE_BUFFER_BYTES + 4);
    mBuffer.order(ByteOrder.nativeOrder());

    if (IO_TYPE.isWriteCache()) {
      if (!TFS.hasLocalWorker()) {
        throw new IOException("No local worker on this machine.");
      }
      File localFolder = TFS.createAndGetUserTempFolder();
      if (localFolder == null) {
        throw new IOException("Failed to create temp user folder for tachyon client.");
      }
      String localFilePath = localFolder.getPath() + "/" + FID;
      mLocalFile = new RandomAccessFile(localFilePath, "rw");
      mLocalFileChannel = mLocalFile.getChannel();
      mSizeBytes = 0;
      LOG.info("File " + localFilePath + " was created!");
    }

    if (IO_TYPE.isWriteThrough()) {
      String underfsFolder = TFS.createAndGetUserUnderfsTempFolder();
      UnderFileSystem underfsClient = UnderFileSystem.get(underfsFolder);
      mCheckpointOutputStream = underfsClient.create(underfsFolder + "/" + FID);
    }
  }

  // TODO mBuffer.limit() seems wrong here, unit test it to confirm.
  private synchronized void appendCurrentBuffer(int minimalPosition) throws IOException {
    if (mBuffer.position() >= minimalPosition) {
      if (IO_TYPE.isWriteCache()) {
        if (Constants.DEBUG && mSizeBytes != mLocalFile.length()) {
          CommonUtils.runtimeException(
              String.format("mSize (%d) != mFile.length() (%d)", mSizeBytes, mLocalFile.length()));
        }

        if (!TFS.requestSpace(mBuffer.position())) {
          if (TFS.isNeedPin(FID)) {
            TFS.outOfMemoryForPinFile(FID);
            throw new IOException("Local tachyon worker does not have enough " +
                "space or no worker for " + FID);
          }
          throw new IOException("Local tachyon worker does not have enough space.");
        }
        mBuffer.flip();
        MappedByteBuffer out = 
            mLocalFileChannel.map(MapMode.READ_WRITE, mSizeBytes, mBuffer.limit());
        out.put(mBuffer);
      }

      if (IO_TYPE.isWriteThrough()) {
        mBuffer.flip();
        mCheckpointOutputStream.write(mBuffer.array(), 0, mBuffer.limit());
      }

      mSizeBytes += mBuffer.limit();
      mBuffer.clear();
    }
  }

  @Override
  public void write(int b) throws IOException {
    appendCurrentBuffer(USER_CONF.FILE_BUFFER_BYTES);

    mBuffer.put((byte) (b & 0xFF));
  }

  @Override
  public void write(byte b[]) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (b == null) {
      throw new NullPointerException();
    } else if ((off < 0) || (off > b.length) || (len < 0) ||
        ((off + len) > b.length) || ((off + len) < 0)) {
      throw new IndexOutOfBoundsException();
    }

    if (mBuffer.position() + len >= USER_CONF.FILE_BUFFER_BYTES) {
      if (IO_TYPE.isWriteCache()) {
        if (Constants.DEBUG && mSizeBytes != mLocalFile.length()) {
          CommonUtils.runtimeException(
              String.format("mSize (%d) != mFile.length() (%d)", mSizeBytes, mLocalFile.length()));
        }

        if (!TFS.requestSpace(mBuffer.position() + len)) {
          if (TFS.isNeedPin(FID)) {
            TFS.outOfMemoryForPinFile(FID);
            throw new IOException("Local tachyon worker does not have enough " +
                "space or no worker for " + FID);
          }
          throw new IOException("Local tachyon worker does not have enough space or no worker.");
        }

        mBuffer.flip();
        MappedByteBuffer out =
            mLocalFileChannel.map(MapMode.READ_WRITE, mSizeBytes, mBuffer.limit() + len);
        out.put(mBuffer);
        out.put(b, off, len);
      }

      if (IO_TYPE.isWriteThrough()) {
        mBuffer.flip();
        mCheckpointOutputStream.write(mBuffer.array(), 0, mBuffer.limit());
        mCheckpointOutputStream.write(b, off, len);
      }

      mSizeBytes += mBuffer.limit() + len;
      mBuffer.clear();
    } else {
      mBuffer.put(b, off, len);
    }
  }

  public void write(ByteBuffer buf) throws IOException {
    write(buf.array(), buf.position(), buf.limit() - buf.position());
  }

  public void write(ArrayList<ByteBuffer> bufs) throws IOException {
    for (int k = 0; k < bufs.size(); k ++) {
      write(bufs.get(k));
    }
  }

  public void cancel() throws IOException {
    mCancel = true;
    close();
  }

  @Override
  public void close() throws IOException {
    if (!mClosed) {
      if (!mCancel) {
        appendCurrentBuffer(1);
      }

      if (mLocalFileChannel != null) {
        mLocalFileChannel.close();
        mLocalFile.close();
      }

      if (mCancel) {
        TFS.releaseSpace(mSizeBytes);
      } else {
        if (IO_TYPE.isWriteThrough()) {
          mCheckpointOutputStream.flush();
          mCheckpointOutputStream.close();
          TFS.addCheckpoint(FID);
        }

        if (IO_TYPE.isWriteCache()) {
          try {
            TFS.cacheFile(FID);
          } catch (IOException e) {
            if (IO_TYPE == OpType.WRITE_CACHE) {
              throw e;
            }
          }
        }
      }
    }
    mClosed = true;
  }
}
