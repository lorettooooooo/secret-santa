package it.secretsanta;
import jakarta.persistence.*;
import java.util.UUID;
@Entity
class Participant {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
  @Column(nullable=false) String name;
  @Column(nullable=false, unique=true) String email;
  @Column(length=2000) String interests;
  @Column(length=2000) String ideas;
  @Column(length=2000) String avoid;
  @Column(nullable=false, unique=true) String accessToken = UUID.randomUUID().toString();
  String assignedName;
  @Column(length=2000) String assignedInterests;
  @Column(length=2000) String assignedIdeas;
  @Column(length=2000) String assignedAvoid;
  boolean drawn = false;
}
