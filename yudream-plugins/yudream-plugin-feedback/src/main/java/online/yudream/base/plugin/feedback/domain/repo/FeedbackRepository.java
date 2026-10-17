package online.yudream.base.plugin.feedback.domain.repo;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.feedback.domain.aggregate.Feedback;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackQuery;

/** 反馈仓储：查询按条件在存储层之上统一实现，分页与总数口径由实现保证一致。 */
public interface FeedbackRepository {

    Feedback save(Feedback feedback);

    Optional<Feedback> findById(String id);

    /** 用户端按归属读取：ID 与作者同时命中才返回，避免越权读取他人反馈。 */
    Optional<Feedback> findByIdAndAuthorId(String id, String authorId);

    List<Feedback> findByQuery(FeedbackQuery query);

    long countByQuery(FeedbackQuery query);

    /** 全量扫描，仅供管理端统计使用。 */
    List<Feedback> findAll();

    void delete(Feedback feedback);
}
