package it.secretsanta;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface ParticipantRepository extends JpaRepository<Participant,Long> {
  Optional<Participant> findByEmailIgnoreCase(String email);
  Optional<Participant> findByAccessToken(String token);
  List<Participant> findAllByOrderByNameAsc();
}
