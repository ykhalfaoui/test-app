package com.example.kyc.hits;
import com.example.kyc.party.Party;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="HITS")
public class Hit {
  @Id private String id;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="PARTY_ID")
  private Party party;
  private String hitType;
  private OffsetDateTime occurredAt;
  @Lob private String payloadJson;
  private String status;

  @PrePersist void pre(){ if (id==null) id = UUID.randomUUID().toString(); if (occurredAt==null) occurredAt=OffsetDateTime.now(); }

  public String getId(){return id;} public void setId(String id){this.id=id;}
  public Party getParty(){return party;} public void setParty(Party party){this.party=party;}
  public String getHitType(){return hitType;} public void setHitType(String v){this.hitType=v;}
  public OffsetDateTime getOccurredAt(){return occurredAt;} public void setOccurredAt(OffsetDateTime v){this.occurredAt=v;}
  public String getPayloadJson(){return payloadJson;} public void setPayloadJson(String v){this.payloadJson=v;}
  public String getStatus(){return status;} public void setStatus(String v){this.status=v;}
}
