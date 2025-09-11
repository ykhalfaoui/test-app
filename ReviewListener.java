package com.example.kyc.review;
import com.example.kyc.hits.api.HitQualified;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Component
public class ReviewListener {
  private final ReviewService service;
  public ReviewListener(ReviewService service){ this.service = service; }

  @ApplicationModuleListener @Transactional
  public void on(HitQualified e){
    // Skeleton: start a review with one target on the pivot (NAME_SCREENING)
    service.startReview(e.hitId(), e.partyId(), List.of(new ReviewService.Target(e.partyId(), "NAME_SCREENING")));
  }
}
