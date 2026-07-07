package io.github.armanayvazyan.propsy;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Bottom tool window host. Implemented in Java (not Kotlin) on purpose: a Kotlin
 * implementation of the {@link ToolWindowFactory} interface emits concrete bridge
 * overrides for the interface's default methods ({@code getAnchor()}, {@code getIcon()},
 * {@code manage(...)}), which are {@code @ApiStatus.Internal} and trip verifyPlugin's
 * INTERNAL_API_USAGES gate. Java inherits those defaults without generating overrides.
 */
public final class PropsyToolWindowFactory implements ToolWindowFactory {

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PropsyTablePanel panel = new PropsyTablePanel(project, toolWindow.getDisposable());
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
