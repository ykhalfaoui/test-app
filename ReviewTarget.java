package com.example.kyc.review;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity @Table(name="REVIEW_TARGETS")
public class ReviewTarget {
  @EmbeddedId private ReviewTargetId id;
  private String blockVersionId; // optional until finalized
  private String state;          // PENDING | DONE
  private String finalStatus;    // APPROVED | REJECTED | ...
  private OffsetDateTime finalizedAt;
  public ReviewTarget(){} public ReviewTarget(ReviewTargetId id){ this.id=id; this.state="PENDING"; }
  public ReviewTargetId getId(){return id;} public void setId(ReviewTargetId id){this.id=id;}
  public String getBlockVersionId(){return blockVersionId;} public void setBlockVersionId(String v){this.blockVersionId=v;}
  public String getState(){return state;} public void setState(String v){this.state=v;}
  public String getFinalStatus(){return finalStatus;} public void setFinalStatus(String v){this.finalStatus=v;}
  public java.time.OffsetDateTime getFinalizedAt(){return finalizedAt;} public void setFinalizedAt(java.time.OffsetDateTime v){this.finalizedAt=v;}
}
