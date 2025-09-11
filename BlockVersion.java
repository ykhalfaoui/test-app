package com.example.kyc.blocks;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="BLOCK_VERSIONS", uniqueConstraints=@UniqueConstraint(columnNames={"BLOCK_ID","VERSION_NO"}))
public class BlockVersion {
  @Id private String id;
  @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="BLOCK_ID")
  private Block block;
  private int versionNo;
  private OffsetDateTime validFrom;
  private OffsetDateTime validTo;
  private String status; // IN_REVIEW | PENDING_AGENT | APPROVED | REJECTED
  @Lob private String dataJson;

  @PrePersist void pre(){ if(id==null) id=UUID.randomUUID().toString(); if(validFrom==null) validFrom=OffsetDateTime.now(); }

  public String getId(){return id;} public void setId(String id){this.id=id;}
  public Block getBlock(){return block;} public void setBlock(Block block){this.block=block;}
  public int getVersionNo(){return versionNo;} public void setVersionNo(int v){this.versionNo=v;}
  public OffsetDateTime getValidFrom(){return validFrom;} public void setValidFrom(OffsetDateTime v){this.validFrom=v;}
  public OffsetDateTime getValidTo(){return validTo;} public void setValidTo(OffsetDateTime v){this.validTo=v;}
  public String getStatus(){return status;} public void setStatus(String v){this.status=v;}
  public String getDataJson(){return dataJson;} public void setDataJson(String v){this.dataJson=v;}
}
