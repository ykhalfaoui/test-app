package com.example.kyc.blocks;
import com.example.kyc.blocks.api.BlockVersionFinalized;
import com.example.kyc.party.Party;
import com.example.kyc.party.PartyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockService {
  private final PartyRepository parties;
  private final BlockRepository blocks;
  private final BlockVersionRepository versions;
  private final ApplicationEventPublisher events;

  public BlockService(PartyRepository parties, BlockRepository blocks, BlockVersionRepository versions, ApplicationEventPublisher events){
    this.parties=parties; this.blocks=blocks; this.versions=versions; this.events=events;
  }

  @Transactional
  public BlockVersion ensureOpenVersion(String partyId, String kind){
    Party p = parties.findById(partyId).orElseThrow();
    Block b = blocks.findByPartyAndKind(p, kind).orElseGet(() -> { Block nb = new Block(); nb.setParty(p); nb.setKind(kind); return blocks.save(nb); });
    var current = versions.findCurrent(b.getId());
    if (current.isPresent()) return current.get();
    BlockVersion v = new BlockVersion();
    v.setBlock(b); v.setVersionNo(versions.maxVersionNo(b.getId()) + 1);
    v.setStatus("IN_REVIEW");
    return versions.save(v);
  }

  @Transactional
  public void finalizeVersion(String versionId, String finalStatus){
    var v = versions.findById(versionId).orElseThrow();
    if (v.getValidTo()==null) v.setValidTo(java.time.OffsetDateTime.now());
    v.setStatus(finalStatus);
    versions.save(v);
    events.publishEvent(new BlockVersionFinalized(v.getId(), v.getBlock().getParty().getId(), v.getBlock().getKind(), finalStatus));
  }
}
