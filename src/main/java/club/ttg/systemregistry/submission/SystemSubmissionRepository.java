package club.ttg.systemregistry.submission;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SystemSubmissionRepository extends JpaRepository<SystemSubmission, UUID> {

    @Query("""
            select s from SystemSubmission s
            where s.systemId = :systemId
              and s.status in (club.ttg.systemregistry.submission.SubmissionStatus.PENDING,
                               club.ttg.systemregistry.submission.SubmissionStatus.APPROVED)
            """)
    List<SystemSubmission> findLiveBySystemId(String systemId);

    List<SystemSubmission> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    Page<SystemSubmission> findByStatusIn(Collection<SubmissionStatus> statuses, Pageable pageable);

    @Query("""
            select s from SystemSubmission s
            where s.status = club.ttg.systemregistry.submission.SubmissionStatus.APPROVED
            order by s.systemName
            """)
    List<SystemSubmission> findApproved();

    @Query("""
            select s from SystemSubmission s
            where s.systemId = :systemId
              and s.status = club.ttg.systemregistry.submission.SubmissionStatus.APPROVED
            """)
    Optional<SystemSubmission> findApprovedBySystemId(String systemId);
}
