package com.example.kyc.hits;
import com.example.kyc.hits.api.HitQualified;
import com.example.kyc.party.Party;
import com.example.kyc.party.PartyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HitService {
  private final HitRepository hits;
  private final PartyRepository parties;
  private final ApplicationEventPublisher events;

  public HitService(HitRepository hits, PartyRepository parties, ApplicationEventPublisher events){
    this.hits=hits; this.parties=parties; this.events=events;
  }

  @Transactional
  public String receiveQualifiedHit(String partyId, String hitType){
    Party p = parties.findById(partyId).orElseThrow();
    Hit h = new Hit(); h.setParty(p); h.setHitType(hitType); h.setStatus("QUALIFIED_TRUE");
    hits.save(h);
    events.publishEvent(new HitQualified(h.getId(), p.getId(), hitType));
    return h.getId();
  }
}
