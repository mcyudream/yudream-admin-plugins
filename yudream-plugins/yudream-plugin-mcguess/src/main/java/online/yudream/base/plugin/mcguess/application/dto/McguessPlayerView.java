package online.yudream.base.plugin.mcguess.application.dto;

/**
 * 玩家战绩视图：七种模式的参与 / 胜场（比大小只有最佳连击，无群对局场次），
 * 另含累计猜测、比大小最佳连击与图鉴收集数。
 */
public record McguessPlayerView(String userId, String qq, String nickname,
                                int itemPlayed, int itemWins, int mobPlayed, int mobWins,
                                int recipePlayed, int recipeWins,
                                int fogPlayed, int fogWins, int quizPlayed, int quizWins,
                                int bingoPlayed, int bingoWins, int spotPlayed, int spotWins,
                                int totalGuesses, int holBest, int collectionCount, long updatedAt) {
}
