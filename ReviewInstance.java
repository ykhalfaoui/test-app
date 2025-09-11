package com.example.kyc.review;
import com.example.kyc.hits.Hit;
import com.example.kyc.party.Party;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="REVIEW_INSTANCE")
public class ReviewInstance {
  @Id private String id;
  @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="HIT_ID")
  private Hit hit;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="PIVOT_PARTY_ID")
  private Party pivotParty;
  private OffsetDateTime startedAt;
  private OffsetDateTime closedAt;
  private String notes;
  @PrePersist void pre(){ if(id==null) id=UUID.randomUUID().toString(); if(startedAt==null) startedAt=OffsetDateTime.now(); }
  public String getId(){return id;} public void setId(String id){this.id=id;}
  public Hit getHit(){return hit;} public void setHit(Hit hit){this.hit=hit;}
  public Party getPivotParty(){return pivotParty;} public void setPivotParty(Party pivotParty){this.pivotParty=pivotParty;}
  public OffsetDateTime getStartedAt(){return startedAt;} public void setStartedAt(OffsetDateTime v){this.startedAt=v;}
  public OffsetDateTime getClosedAt(){return closedAt;} public void setClosedAt(OffsetDateTime v){this.closedAt=v;}
  public String getNotes(){return notes;} public void setNotes(String notes){this.notes=notes;}
}
