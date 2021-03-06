package com.google.dart.tools.internal.corext.refactoring.rename;

import com.google.dart.tools.core.model.Type;
import com.google.dart.tools.core.search.SearchEngine;
import com.google.dart.tools.core.search.SearchEngineFactory;
import com.google.dart.tools.core.search.SearchMatch;
import com.google.dart.tools.internal.corext.refactoring.Checks;
import com.google.dart.tools.internal.corext.refactoring.RefactoringAvailabilityTester;
import com.google.dart.tools.internal.corext.refactoring.RefactoringCoreMessages;
import com.google.dart.tools.internal.corext.refactoring.util.ExecutionUtils;
import com.google.dart.tools.internal.corext.refactoring.util.RunnableObjectEx;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

import java.util.List;

/**
 * {@link DartRenameProcessor} for {@link Type}.
 * 
 * @coverage dart.editor.ui.refactoring.core
 */
public class RenameTypeProcessor extends RenameTopLevelProcessor {

  public static final String IDENTIFIER = "com.google.dart.tools.ui.renameTypeProcessor"; //$NON-NLS-1$

  private final Type type;

  /**
   * @param type the {@link Type} to rename, not <code>null</code>.
   */
  public RenameTypeProcessor(Type type) {
    super(type);
    this.type = type;
  }

  @Override
  public RefactoringStatus checkNewElementName(String newName) throws CoreException {
    RefactoringStatus result = Checks.checkTypeName(newName);
    result.merge(super.checkNewElementName(newName));
    return result;
  }

  @Override
  public String getIdentifier() {
    return IDENTIFIER;
  }

  @Override
  public Object getNewElement() {
    return type.getCompilationUnit().getType(getNewElementName());
  }

  @Override
  public String getProcessorName() {
    return RefactoringCoreMessages.RenameTypeRefactoring_name;
  }

  @Override
  public boolean isApplicable() throws CoreException {
    return RefactoringAvailabilityTester.isRenameAvailable(type);
  }

  @Override
  protected List<SearchMatch> getReferences(final IProgressMonitor pm) throws CoreException {
    return ExecutionUtils.runObjectCore(new RunnableObjectEx<List<SearchMatch>>() {
      @Override
      public List<SearchMatch> runObject() throws Exception {
        SearchEngine searchEngine = SearchEngineFactory.createSearchEngine();
        return searchEngine.searchReferences(type, null, null, pm);
      }
    });
  }
}
