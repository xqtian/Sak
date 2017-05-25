package com.everhomes.sak.doccomment;

import com.everhomes.sak.config.IdeaToolsSetting;
import com.everhomes.sak.util.PsiUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiClass;

/**
 * Created by xq.tian on 2017/5/23.
 */
public class DocCommentAction extends AnAction {

    private IdeaToolsSetting settings;

    public DocCommentAction() {
        this.settings = ServiceManager.getService(IdeaToolsSetting.class);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        PsiClass psiClass = PsiUtil.getPsiClass(e);
        if (psiClass == null) {
            errDialog("Do not find class");
            return;
        }

        String result = DocCommentService.genRestDocComment(psiClass, settings);
        if (!"OK".equals(result)) {
            errDialog(result);
        }
    }

    private void errDialog(String message) {
        Messages.showMessageDialog(
                message,
                "Error",
                Messages.getInformationIcon()
        );
    }
}