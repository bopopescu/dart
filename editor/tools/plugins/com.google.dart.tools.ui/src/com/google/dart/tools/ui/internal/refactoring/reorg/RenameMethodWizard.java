package com.google.dart.tools.ui.internal.refactoring.reorg;

import com.google.dart.tools.ui.DartPluginImages;
import com.google.dart.tools.ui.internal.refactoring.RefactoringMessages;
import com.google.dart.tools.ui.internal.text.DartHelpContextIds;

import org.eclipse.ltk.core.refactoring.Refactoring;

/**
 * @coverage dart.editor.ui.refactoring.ui
 */
public class RenameMethodWizard extends RenameRefactoringWizard {

  public RenameMethodWizard(Refactoring refactoring) {
    super(
        refactoring,
        RefactoringMessages.RenameMethodWizard_defaultPageTitle,
        RefactoringMessages.RenameMethodWizard_inputPage_description,
        DartPluginImages.DESC_WIZBAN_REFACTOR_METHOD,
        DartHelpContextIds.RENAME_METHOD_WIZARD_PAGE);
  }
}
