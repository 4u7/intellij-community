// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.services;

import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.DataProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseEvent;

@ApiStatus.Experimental
public interface ServiceViewDescriptor {
  @NotNull
  ItemPresentation getPresentation();

  @Nullable
  default JComponent getContentComponent() {
    return null;
  }

  @Nullable
  default ActionGroup getToolbarActions() {
    return null;
  }

  @Nullable
  default ActionGroup getPopupActions() {
    return getToolbarActions();
  }

  @Nullable
  default DataProvider getDataProvider() {
    return null;
  }

  default void onNodeSelected() {
  }

  default void onNodeUnselected() {
  }

  default boolean handleDoubleClick(@NotNull MouseEvent event) {
    return false;
  }
}
