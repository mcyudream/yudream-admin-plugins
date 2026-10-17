package online.yudream.base.plugin.todo.interfaces.request;

import java.util.List;

/** 添加参与人请求体：把指定用户加入某条已有任务的参与人列表，不新建任务副本。 */
public record TodoParticipantRequest(
        List<String> userIds
) {
}
