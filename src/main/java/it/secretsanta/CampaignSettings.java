package it.secretsanta;
import jakarta.persistence.*;
// riga unica (id=1) con le impostazioni della campagna
@Entity
class CampaignSettings {
  @Id Long id = 1L;
  String budget;
  @Column(length=2000) String notes;
  int maxParticipants = 20;
}
