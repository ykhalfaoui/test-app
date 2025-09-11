package com.example.kyc.blocks;
import com.example.kyc.party.Party;
import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="BLOCKS", uniqueConstraints = @UniqueConstraint(columnNames = {"PARTY_ID","KIND"}))
public class Block {
  @Id private String id;
  @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="PARTY_ID")
  private Party party;
  private String kind;
  @PrePersist void pre(){ if(id==null) id=UUID.randomUUID().toString(); }
  public String getId(){return id;} public void setId(String id){this.id=id;}
  public Party getParty(){return party;} public void setParty(Party party){this.party=party;}
  public String getKind(){return kind;} public void setKind(String kind){this.kind=kind;}
}
