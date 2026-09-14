package online.yudream.base.plugin.launcher.interfaces.res;

import java.util.List;

public record PackDetailRes(
        PackRes pack,
        List<PackVersionRes> versions
) {
}
