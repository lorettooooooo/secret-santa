package it.secretsanta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PasswordsTest {
  @Test
  void generatedPasswordsFollowTheChristmasPattern() {
    for (int i = 0; i < 1000; i++) {
      String p = Passwords.generate();
      assertEquals(12, p.length(), p);
      assertTrue(p.matches("[A-Z][a-z]{7}\\d{3}[!?#$%&*@]"), p);
    }
  }
  @Test
  void sha256MatchesKnownVector() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Passwords.sha256("abc"));
  }
}
