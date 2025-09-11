package com.example.kyc.relations;
import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="RELATION_TYPE_BLOCK_SCOPE", uniqueConstraints=@UniqueConstraint(columnNames={"RELATION_TYPE","BLOCK_KIND"}))
public class RelationTypeBlockScope {
  @Id private String id;
  private String relationType;
  private String blockKind;
  private boolean isRequired;
  private String policyCode;
  @PrePersist void pre(){ if(id==null) id=UUID.randomUUID().toString(); }
  public String getId(){return id;} public void setId(String id){this.id=id;}
  public String getRelationType(){return relationType;} public void setRelationType(String v){this.relationType=v;}
  public String getBlockKind(){return blockKind;} public void setBlockKind(String v){this.blockKind=v;}
  public boolean isRequired(){return isRequired;} public void setRequired(boolean v){this.isRequired=v;}
  public String getPolicyCode(){return policyCode;} public void setPolicyCode(String v){this.policyCode=v;}
}
