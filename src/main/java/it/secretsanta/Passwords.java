package it.secretsanta;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
final class Passwords {
  // parole di 8 lettere: 8 + 3 cifre + 1 simbolo = 12 caratteri
  private static final String[] WORDS = {"Reindeer","Snowball","Stocking","Ornament","Snowfall","Chestnut","Cinnamon","Presents",
    "Pinecone","Yuletide","Fireside","Frosting","Presepio","Stellina","Panforte","Zampogna"};
  private static final String SYMBOLS = "!?#$%&*@";
  private static final SecureRandom RANDOM = new SecureRandom();
  private Passwords() {}
  static String generate() {
    return WORDS[RANDOM.nextInt(WORDS.length)] + String.format("%03d", RANDOM.nextInt(1000)) + SYMBOLS.charAt(RANDOM.nextInt(SYMBOLS.length()));
  }
  static String sha256(String s) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
