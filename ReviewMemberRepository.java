package com.example.kyc.review;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ReviewMemberRepository extends JpaRepository<ReviewMember, String> {
  List<ReviewMember> findByReviewId(String reviewId);
}
