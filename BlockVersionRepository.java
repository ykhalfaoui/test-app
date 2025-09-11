package com.example.kyc.blocks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface BlockVersionRepository extends JpaRepository<BlockVersion, String> {
  @Query("select v from BlockVersion v where v.block.id = :blockId and v.validTo is null")
  Optional<BlockVersion> findCurrent(@Param("blockId") String blockId);
  @Query("select coalesce(max(v.versionNo),0) from BlockVersion v where v.block.id = :blockId")
  int maxVersionNo(@Param("blockId") String blockId);
}
