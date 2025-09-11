package com.example.kyc.review;
import com.example.kyc.party.Party;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="REVIEW_MEMBER", uniqueConstraints=@UniqueConstraint(columnNames={"REVIEW_ID","MEMBER_PARTY_ID","RELATION_TYPE"}))
public class ReviewMember {
  @Id private String id;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="REVIEW_ID")
  private ReviewInstance review;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="MEMBER_PARTY_ID")
  private Party memberParty;
  private String relationType;
  private OffsetDateTime addedAt;
  @PrePersist void pre(){ if(id==null) id=UUID.randomUUID().toString(); if(addedAt==null) addedAt=OffsetDateTime.now(); }
  public String getId(){return id;} public void setId(String id){this.id=id;}
  public ReviewInstance getReview(){return review;} public void setReview(ReviewInstance r){this.review=r;}
  public Party getMemberParty(){return memberParty;} public void setMemberParty(Party p){this.memberParty=p;}
  public String getRelationType(){return relationType;} public void setRelationType(String v){this.relationType=v;}
  public OffsetDateTime getAddedAt(){return addedAt;} public void setAddedAt(OffsetDateTime v){this.addedAt=v;}
}
