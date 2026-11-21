package info.trizub.clamav.webclient.identity;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.naming.Context;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import javax.net.ssl.*;

/** Pure-JDK wire-level regression: a trusted StartTLS certificate for another host MUST fail. */
public final class StartTlsJndiMismatchProbe {
  private StartTlsJndiMismatchProbe() {}

  public static void verify() throws Exception {
    Path keystore = Files.createTempFile("clamav-ldap-mismatched-san-", ".p12");
    try {
      Files.delete(keystore); // keytool requires a new path
      String password = "temporary-fixture-password";
      String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
      var command =
          new ProcessBuilder(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  "ldap-mismatch",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  keystore.toString(),
                  "-storepass",
                  password,
                  "-keypass",
                  password,
                  "-dname",
                  "CN=wrong-name.example.test",
                  "-ext",
                  "SAN=dns:wrong-name.example.test",
                  "-validity",
                  "1",
                  "-noprompt")
              .redirectErrorStream(true)
              .start();
      String output = new String(command.getInputStream().readAllBytes());
      if (!command.waitFor(30, TimeUnit.SECONDS) || command.exitValue() != 0)
        throw new IllegalStateException(
            "Could not generate isolated TLS test certificate: " + output);
      KeyStore keys = KeyStore.getInstance("PKCS12");
      try (var in = Files.newInputStream(keystore)) {
        keys.load(in, password.toCharArray());
      }
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keys, password.toCharArray());
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(
          keys); // Trust the WRONG host's certificate so only hostname verification can reject.
      SSLContext serverTls = SSLContext.getInstance("TLS");
      serverTls.init(kmf.getKeyManagers(), null, new SecureRandom());
      SSLContext clientTls = SSLContext.getInstance("TLS");
      clientTls.init(null, tmf.getTrustManagers(), new SecureRandom());
      try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        listener.setSoTimeout(7000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
          Future<?> server = executor.submit(() -> serve(listener, serverTls));
          Hashtable<String, Object> environment = new Hashtable<>();
          environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
          environment.put(
              Context.PROVIDER_URL,
              "ldap://"
                  + listener.getInetAddress().getHostAddress()
                  + ":"
                  + listener.getLocalPort());
          environment.put(Context.SECURITY_AUTHENTICATION, "none");
          InitialLdapContext context = new InitialLdapContext(environment, null);
          try {
            StartTlsResponse tls =
                (StartTlsResponse) context.extendedOperation(new StartTlsRequest());
            boolean rejected = false;
            try {
              tls.negotiate(clientTls.getSocketFactory());
            } catch (IOException expected) {
              if (!(expected instanceof SSLPeerUnverifiedException)
                  || expected.getMessage() == null
                  || !expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("hostname"))
                throw new AssertionError(
                    "StartTLS was rejected for a reason other than hostname mismatch", expected);
              rejected = true;
            }
            if (!rejected)
              throw new AssertionError(
                  "Trusted but hostname-mismatched StartTLS certificate was accepted");
          } finally {
            context.close();
          }
          server.get(8, TimeUnit.SECONDS);
        } finally {
          listener.close();
          executor.shutdownNow();
        }
      }
    } finally {
      Files.deleteIfExists(keystore);
    }
  }

  private static void serve(ServerSocket listener, SSLContext serverTls) {
    try (Socket incoming = listener.accept()) {
      incoming.setSoTimeout(7000);
      InputStream input = incoming.getInputStream();
      OutputStream output = incoming.getOutputStream();
      if (input.read() != 0x30) throw new IOException("Unexpected first LDAP frame");
      int length = input.read();
      if (length > 127) {
        int bytes = length & 0x7f;
        length = 0;
        for (int i = 0; i < bytes; i++) length = (length << 8) | input.read();
      }
      byte[] request = input.readNBytes(length);
      if (request.length != length || request[0] != 0x02 || request[1] != 0x01)
        throw new IOException("Unexpected LDAP BER request");
      byte id = request[2];
      byte[] response = {
        0x30, 0x0c, 0x02, 0x01, id, 0x78, 0x07, 0x0a, 0x01, 0x00, 0x04, 0x00, 0x04, 0x00
      };
      output.write(response);
      output.flush();
      try (SSLSocket tls =
          (SSLSocket)
              serverTls
                  .getSocketFactory()
                  .createSocket(
                      incoming,
                      incoming.getInetAddress().getHostAddress(),
                      incoming.getPort(),
                      false)) {
        tls.setUseClientMode(false);
        tls.startHandshake();
        // The client is expected to terminate after detecting the wrong hostname.
      } catch (SSLException expected) {
        /* peer rejected the certificate */
      }
    } catch (Exception e) {
      throw new IllegalStateException("Local StartTLS test fixture failed", e);
    }
  }

  public static void main(String[] args) throws Exception {
    verify();
    System.out.println("PASS: trusted StartTLS certificate with mismatched SAN was rejected");
  }
}
