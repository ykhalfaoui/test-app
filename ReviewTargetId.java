package com.example.kyc.review;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
@Embeddable
public class ReviewTargetId implements Serializable {
  private String reviewId;
  private String targetPartyId;
  private String blockKind;
  public ReviewTargetId(){}
  public ReviewTargetId(String reviewId, String targetPartyId, String blockKind){
    this.reviewId=reviewId; this.targetPartyId=targetPartyId; this.blockKind=blockKind;
  }
  public String getReviewId(){return reviewId;} public void setReviewId(String v){this.reviewId=v;}
  public String getTargetPartyId(){return targetPartyId;} public void setTargetPartyId(String v){this.targetPartyId=v;}
  public String getBlockKind(){return blockKind;} public void setBlockKind(String v){this.blockKind=v;}
  @Override public boolean equals(Object o){ if(this==o) return true; if(!(o instanceof ReviewTargetId)) return false; ReviewTargetId that=(ReviewTargetId)o; return Objects.equals(reviewId,that.reviewId)&&Objects.equals(targetPartyId,that.targetPartyId)&&Objects.equals(blockKind,that.blockKind); }
  @Override public int hashCode(){ return Objects.hash(reviewId, targetPartyId, blockKind); }
}
