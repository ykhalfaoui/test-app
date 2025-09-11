package com.example.kyc.review;
import com.example.kyc.hits.Hit;
import com.example.kyc.hits.HitRepository;
import com.example.kyc.party.Party;
import com.example.kyc.party.PartyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ReviewService {
  private final ReviewInstanceRepository reviews;
  private final ReviewTargetRepository targets;
  private final HitRepository hits;
  private final PartyRepository parties;
  private final ApplicationEventPublisher events;

  public ReviewService(ReviewInstanceRepository reviews, ReviewTargetRepository targets, HitRepository hits, PartyRepository parties, ApplicationEventPublisher events){
    this.reviews=reviews; this.targets=targets; this.hits=hits; this.parties=parties; this.events=events;
  }

  @Transactional
  public String startReview(String hitId, String partyId, List<Target> targetList){
    Hit hit = hits.findById(hitId).orElseThrow();
    Party pivot = parties.findById(partyId).orElseThrow();
    ReviewInstance r = new ReviewInstance(); r.setHit(hit); r.setPivotParty(pivot);
    reviews.save(r);
    for (Target t : targetList) {
      ReviewTargetId id = new ReviewTargetId(r.getId(), t.partyId(), t.kind());
      if (!targets.existsById(id)) targets.save(new ReviewTarget(id));
    }
    return r.getId();
  }

  public record Target(String partyId, String kind) {}
}
