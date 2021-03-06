/*
 * ************************************************************************
 * Copyright (C) 2012 Mennē Software Solutions, LLC
 *
 * This code is released as open source under the Apache 2.0 License:<br/>
 * <a href="http://www.apache.org/licenses/LICENSE-2.0">
 * http://www.apache.org/licenses/LICENSE-2.0</a><br />
 * ************************************************************************
 */

package com.moneydance.modules.features.ratios;

import com.moneydance.awt.HSLColorUtil;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * Changes the background of the specified parent container when keyboard focus is gained or lost.
 *
 * @author Kevin Menningen
 */
class ColoredParentFocusAdapter extends FocusAdapter
{
    private final JPanel _parent;
    private final Color _normalBackground;
    private final Color _focusedBackground;
    private boolean _colorChangeEnabled = true;

    ColoredParentFocusAdapter(final JPanel parent)
    {
        _parent = parent;
        _parent.setOpaque(true);
        _normalBackground = parent.getBackground();
        Color adjustedColor = _normalBackground;

        // surround in try/catch in case HSL color utilities are not available
        try
        {
            // derive a new color, either darker or lighter depending upon the normal background
            final float[] hslBackground = HSLColorUtil.convertRGBtoHSL(_normalBackground);
            if (hslBackground[HSLColorUtil.LUMINANCE_INDEX] > 0.97f)
            {
                adjustedColor = HSLColorUtil.getLighterColor(hslBackground, -0.03f);
            }
            else
            {
                adjustedColor = HSLColorUtil.getLighterColor(hslBackground, 0.03f);
            }
        }
        catch (Throwable error)
        {
            // ignore, we'll just use the default background
        }
        _focusedBackground = adjustedColor;
    }

    @Override
    public void focusGained(FocusEvent event)
    {
        _parent.setBackground(_focusedBackground);
        _colorChangeEnabled = true;
    }

    @Override
    public void focusLost(FocusEvent event)
    {
        if (_colorChangeEnabled)
        {
            _parent.setBackground(_normalBackground);
        }
    }

    void disableColorChange()
    {
        _colorChangeEnabled = false;
    }
}
