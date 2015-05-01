package org.jruby.puma;

import org.jruby.Ruby;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import static javax.net.ssl.SSLEngineResult.Status;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus;

public class MiniSSL extends RubyObject {
  private static ObjectAllocator ALLOCATOR = new ObjectAllocator() {
    public IRubyObject allocate(Ruby runtime, RubyClass klass) {
      return new MiniSSL(runtime, klass);
    }
  };

  // set to true to switch on our low-fi trace logging
  private static boolean DEBUG = false;

  public static void createMiniSSL(Ruby runtime) {
    RubyModule mPuma = runtime.defineModule("Puma");
    RubyModule ssl = mPuma.defineModuleUnder("MiniSSL");

    mPuma.defineClassUnder("SSLError",
                           runtime.getClass("IOError"),
                           runtime.getClass("IOError").getAllocator());

    RubyClass eng = ssl.defineClassUnder("Engine",runtime.getObject(),ALLOCATOR);
    eng.defineAnnotatedMethods(MiniSSL.class);
  }

  /**
   * Fairly transparent wrapper around {@link java.nio.ByteBuffer} which adds the enhancements we need
   */
  private static class MiniSSLBuffer {
    ByteBuffer buffer;

    private MiniSSLBuffer(int capacity) { buffer = ByteBuffer.allocate(capacity); }
    private MiniSSLBuffer(byte[] initialContents) { buffer = ByteBuffer.wrap(initialContents); }

    public void clear() { buffer.clear(); }
    public void compact() { buffer.compact(); }
    public void flip() { buffer.flip(); }
    public boolean hasRemaining() { return buffer.hasRemaining(); }
    public int position() { return buffer.position(); }

    public ByteBuffer getRawBuffer() {
      return buffer;
    }

    /**
     * Writes bytes to the buffer after ensuring there's room
     */
    public void put(byte[] bytes) {
      if (buffer.remaining() < bytes.length) {
        resize(buffer.limit() + bytes.length);
      }
      buffer.put(bytes);
    }

    /**
     * Ensures that newCapacity bytes can be written to this buffer, only re-allocating if necessary
     */
    public void resize(int newCapacity) {
      if (newCapacity > buffer.capacity()) {
        ByteBuffer dstTmp = ByteBuffer.allocate(newCapacity);
        buffer.flip();
        dstTmp.put(buffer);
        buffer = dstTmp;
      } else {
        buffer.limit(newCapacity);
      }
    }

    /**
     * Drains the buffer to a ByteList, or returns null for an empty buffer
     */
    public ByteList asByteList() {
      buffer.flip();
      if (!buffer.hasRemaining()) {
        buffer.clear();
        return null;
      }

      byte[] bss = new byte[buffer.limit()];

      buffer.get(bss);
      buffer.clear();
      return new ByteList(bss);
    }

    @Override
    public String toString() { return buffer.toString(); }
  }

  private SSLEngine engine;
  private MiniSSLBuffer inboundNetData;
  private MiniSSLBuffer outboundAppData;
  private MiniSSLBuffer outboundNetData;

  public MiniSSL(Ruby runtime, RubyClass klass) {
    super(runtime, klass);
  }

  @JRubyMethod(meta = true)
  public static IRubyObject server(ThreadContext context, IRubyObject recv, IRubyObject miniSSLContext) {
    RubyClass klass = (RubyClass) recv;

    return klass.newInstance(context,
        new IRubyObject[] { miniSSLContext },
        Block.NULL_BLOCK);
  }

  @JRubyMethod
  public IRubyObject initialize(ThreadContext threadContext, IRubyObject miniSSLContext)
      throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

    char[] password = miniSSLContext.callMethod(threadContext, "keystore_pass").convertToString().asJavaString().toCharArray();
    ks.load(new FileInputStream(miniSSLContext.callMethod(threadContext, "keystore").convertToString().asJavaString()),
        password);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(ks, password);

    SSLContext sslCtx = SSLContext.getInstance("TLS");

    sslCtx.init(kmf.getKeyManagers(), null, null);
    engine = sslCtx.createSSLEngine();

    IRubyObject enableSSLv3 = miniSSLContext.callMethod(threadContext, "enable_SSLv3");
    String[] protocols;
    if (enableSSLv3 instanceof RubyBoolean && enableSSLv3.isTrue()) {
      protocols = new String[] { "SSLv2Hello", "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2" };
    } else {
      protocols = new String[] { "SSLv2Hello", "TLSv1", "TLSv1.1", "TLSv1.2" };
    }
    engine.setEnabledProtocols(protocols);
    engine.setUseClientMode(false);

    SSLSession session = engine.getSession();
    inboundNetData = new MiniSSLBuffer(session.getPacketBufferSize());
    outboundAppData = new MiniSSLBuffer(session.getApplicationBufferSize());
    outboundAppData.flip();
    outboundNetData = new MiniSSLBuffer(session.getPacketBufferSize());

    return this;
  }

  @JRubyMethod
  public IRubyObject inject(IRubyObject arg) {
    try {
      byte[] bytes = arg.convertToString().getBytes();

      log("Net Data post pre-inject: " + inboundNetData);
      inboundNetData.put(bytes);
      log("Net Data post post-inject: " + inboundNetData);

      log("inject(): " + bytes.length + " encrypted bytes from request");
      return this;
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private enum SSLOperation {
    WRAP,
    UNWRAP
  }

  private SSLEngineResult doOp(SSLOperation sslOp, MiniSSLBuffer src, MiniSSLBuffer dst) throws SSLException {
    SSLEngineResult res = null;
    boolean retryOp = true;
    while (retryOp) {
      switch (sslOp) {
        case WRAP:
          res = engine.wrap(src.getRawBuffer(), dst.getRawBuffer());
          break;
        case UNWRAP:
          res = engine.unwrap(src.getRawBuffer(), dst.getRawBuffer());
          break;
        default:
          throw new IllegalStateException("Unknown SSLOperation: " + sslOp);
      }

      switch (res.getStatus()) {
        case BUFFER_OVERFLOW:
          log("SSLOp#doRun(): overflow");
          log("SSLOp#doRun(): dst data at overflow: " + dst);
          // increase the buffer size to accommodate the overflowing data
          int newSize = Math.max(engine.getSession().getPacketBufferSize(), engine.getSession().getApplicationBufferSize());
          dst.resize(newSize + dst.position());
          // retry the operation
          retryOp = true;
          break;
        case BUFFER_UNDERFLOW:
          log("SSLOp#doRun(): underflow");
          log("SSLOp#doRun(): src data at underflow: " + src);
          // need to wait for more data to come in before we retry
          retryOp = false;
          break;
        default:
          // other cases are OK and CLOSED.  We're done here.
          retryOp = false;
      }
    }

    // after each op, run any delegated tasks if needed
    if(engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      Runnable runnable;
      while ((runnable = engine.getDelegatedTask()) != null) {
        runnable.run();
      }
    }

    return res;
  }

  @JRubyMethod
  public IRubyObject read() throws Exception {
    try {
      inboundNetData.flip();

      if(!inboundNetData.hasRemaining()) {
        return getRuntime().getNil();
      }

      log("read(): inboundNetData prepped for read: " + inboundNetData);

      MiniSSLBuffer inboundAppData = new MiniSSLBuffer(engine.getSession().getApplicationBufferSize());
      SSLEngineResult res = doOp(SSLOperation.UNWRAP, inboundNetData, inboundAppData);
      log("read(): after initial unwrap", engine, res);

      log("read(): Net Data post unwrap: " + inboundNetData);

      HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
      boolean done = false;
      while (!done) {
        switch (handshakeStatus) {
          case NEED_WRAP:
            res = doOp(SSLOperation.WRAP, inboundAppData, outboundNetData);
            log("read(): after handshake wrap", engine, res);
            break;
          case NEED_UNWRAP:
            res = doOp(SSLOperation.UNWRAP, inboundNetData, inboundAppData);
            log("read(): after handshake unwrap", engine, res);
            if (res.getStatus() == Status.BUFFER_UNDERFLOW) {
              // need more data before we can shake more hands
              done = true;
            }
            break;
          default:
            done = true;
        }
        handshakeStatus = engine.getHandshakeStatus();
      }

      if (inboundNetData.hasRemaining()) {
        log("Net Data post pre-compact: " + inboundNetData);
        inboundNetData.compact();
        log("Net Data post post-compact: " + inboundNetData);
      } else {
        log("Net Data post pre-reset: " + inboundNetData);
        inboundNetData.clear();
        log("Net Data post post-reset: " + inboundNetData);
      }

      ByteList appDataByteList = inboundAppData.asByteList();
      if (appDataByteList == null) {
        return getRuntime().getNil();
      }

      RubyString str = getRuntime().newString("");
      str.setValue(appDataByteList);

      logPlain("\n");
      log("read(): begin dump of request data >>>>\n");
      if (str.asJavaString().getBytes().length < 1000) {
        logPlain(str.asJavaString() + "\n");
      }
      logPlain("Num bytes: " + str.asJavaString().getBytes().length + "\n");
      log("read(): end dump of request data   <<<<\n");
      return str;
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private static void log(String str, SSLEngine engine, SSLEngineResult result) {
    if (DEBUG) {
      log(str + " " + result.getStatus() + "/" + engine.getHandshakeStatus() +
          "---bytes consumed: " + result.bytesConsumed() +
          ", bytes produced: " + result.bytesProduced());
    }
  }

  private static void log(String str) {
    if (DEBUG) {
      System.out.println("MiniSSL.java: " + str);
    }
  }

  private static void logPlain(String str) {
    if (DEBUG) {
      System.out.println(str);
    }
  }

  @JRubyMethod
  public IRubyObject write(IRubyObject arg) {
    try {
      log("write(): begin dump of response data >>>>\n");
      logPlain("\n");
      if (arg.asJavaString().getBytes().length < 1000) {
        logPlain(arg.asJavaString() + "\n");
      }
      logPlain("Num bytes: " + arg.asJavaString().getBytes().length + "\n");
      log("write(): end dump of response data   <<<<\n");

      byte[] bls = arg.convertToString().getBytes();
      outboundAppData = new MiniSSLBuffer(bls);

      return getRuntime().newFixnum(bls.length);
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  @JRubyMethod
  public IRubyObject extract() throws SSLException {
    try {
      ByteList dataByteList = outboundNetData.asByteList();
      if (dataByteList != null) {
        RubyString str = getRuntime().newString("");
        str.setValue(dataByteList);
        return str;
      }

      if (!outboundAppData.hasRemaining()) {
        return getRuntime().getNil();
      }

      outboundNetData.clear();
      SSLEngineResult res = doOp(SSLOperation.WRAP, outboundAppData, outboundNetData);
      log("extract(): bytes consumed: " + res.bytesConsumed() + "\n");
      log("extract(): bytes produced: " + res.bytesProduced() + "\n");
      dataByteList = outboundNetData.asByteList();
      if (dataByteList == null) {
        return getRuntime().getNil();
      }

      RubyString str = getRuntime().newString("");
      str.setValue(dataByteList);

      log("extract(): " + dataByteList.getRealSize() + " encrypted bytes for response");

      return str;
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }
}
