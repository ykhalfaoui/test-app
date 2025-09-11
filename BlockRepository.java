package com.example.kyc.blocks;
import com.example.kyc.party.Party;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface BlockRepository extends JpaRepository<Block, String> {
  Optional<Block> findByPartyAndKind(Party party, String kind);
}
