package com.example.kyc.party;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="PARTY")
public class Party {
  @Id private String id;
  private String externalRef;
  private String type;
  private String subType;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  @PrePersist void pre() {
    if (id == null) id = UUID.randomUUID().toString();
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }
  @PreUpdate void upd(){ updatedAt = OffsetDateTime.now(); }

  public String getId(){return id;} public void setId(String id){this.id=id;}
  public String getExternalRef(){return externalRef;} public void setExternalRef(String v){this.externalRef=v;}
  public String getType(){return type;} public void setType(String v){this.type=v;}
  public String getSubType(){return subType;} public void setSubType(String v){this.subType=v;}
  public OffsetDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(OffsetDateTime v){this.createdAt=v;}
  public OffsetDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(OffsetDateTime v){this.updatedAt=v;}
}
