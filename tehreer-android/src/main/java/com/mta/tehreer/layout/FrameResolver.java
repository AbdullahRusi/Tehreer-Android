/*
 * Copyright (C) 2018 Muhammad Tayyab Akram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mta.tehreer.layout;

import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LeadingMarginSpan.LeadingMarginSpan2;
import android.text.style.LineHeightSpan;
import android.text.style.ParagraphStyle;

import com.mta.tehreer.internal.layout.BreakResolver;
import com.mta.tehreer.internal.layout.ParagraphCollection;
import com.mta.tehreer.internal.layout.RunCollection;
import com.mta.tehreer.unicode.BidiParagraph;

import java.util.ArrayList;
import java.util.List;

public class FrameResolver {

    private LineResolver mLineResolver = new LineResolver();
    private Typesetter mTypesetter;
    private Spanned mSpanned;
    private ParagraphCollection mParagraphs;
    private RunCollection mRuns;
    private byte[] mBreaks;

    private RectF mFrameRect = new RectF(0, 0, 0, 0);
    private TextAlignment mTextAlignment = TextAlignment.INTRINSIC;
    private VerticalAlignment mVerticalAlignment = VerticalAlignment.TOP;
    private BreakMode mTruncationMode = BreakMode.LINE;
    private TruncationPlace mTruncationPlace = TruncationPlace.END;
    private int mMaxLines = 0;
    private float mExtraLineSpacing = 0.0f;
    private float mLineHeightMultiplier = 0.0f;

    public FrameResolver() {
    }

    public Typesetter getTypesetter() {
        return mTypesetter;
    }

    public void setTypesetter(Typesetter typesetter) {
        mTypesetter = typesetter;
        mSpanned = typesetter.getSpanned();
        mParagraphs = typesetter.getParagraphs();
        mRuns = typesetter.getRuns();
        mBreaks = typesetter.getBreaks();
        mLineResolver.reset(mSpanned, mParagraphs, mRuns);
    }

    public RectF getFrameRect() {
        return new RectF(mFrameRect);
    }

    public void setFrameRect(RectF frameRect) {
        if (frameRect == null) {
            throw new NullPointerException("Frame rect is null");
        }

        mFrameRect.set(frameRect);
    }

    public TextAlignment getTextAlignment() {
        return mTextAlignment;
    }

    public void setTextAlignment(TextAlignment textAlignment) {
        mTextAlignment = textAlignment;
    }

    public VerticalAlignment getVerticalAlignment() {
        return mVerticalAlignment;
    }

    public void setVerticalAlignment(VerticalAlignment verticalAlignment) {
        mVerticalAlignment = verticalAlignment;
    }

    public BreakMode getTruncationMode() {
        return mTruncationMode;
    }

    public void setTruncationMode(BreakMode truncationMode) {
        mTruncationMode = truncationMode;
    }

    public TruncationPlace getTruncationPlace() {
        return mTruncationPlace;
    }

    public void setTruncationPlace(TruncationPlace truncationPlace) {
        mTruncationPlace = truncationPlace;
    }

    public int getMaxLines() {
        return mMaxLines;
    }

    public void setMaxLines(int maxLines) {
        mMaxLines = maxLines;
    }

    public float getExtraLineSpacing() {
        return mExtraLineSpacing;
    }

    public void setExtraLineSpacing(float extraLineSpacing) {
        mExtraLineSpacing = extraLineSpacing;
    }

    public float getLineHeightMultiplier() {
        return mLineHeightMultiplier;
    }

    public void setLineHeightMultiplier(float lineHeightMultiplier) {
        mLineHeightMultiplier = lineHeightMultiplier;
    }

    private float getVerticalMultiplier() {
        switch (mVerticalAlignment) {
        case BOTTOM:
            return 1.0f;

        case MIDDLE:
            return 0.5f;

        default:
            return 0.0f;
        }
    }

    private float getFlushFactor(Layout.Alignment layoutAlignment, byte paragraphLevel) {
        boolean isLTR = ((paragraphLevel & 1) == 0);

        if (layoutAlignment != null) {
            switch (layoutAlignment) {
            case ALIGN_NORMAL:
                return (isLTR ? 0.0f : 1.0f);

            case ALIGN_CENTER:
                return 0.5f;

            case ALIGN_OPPOSITE:
                return (isLTR ? 1.0f : 0.0f);
            }
        } else if (mTextAlignment != null) {
            switch (mTextAlignment) {
            case LEFT:
                return 0.0f;

            case RIGHT:
                return 1.0f;

            case CENTER:
                return 0.5f;

            case INTRINSIC:
                return (isLTR ? 0.0f : 1.0f);

            case EXTRINSIC:
                return (isLTR ? 1.0f : 0.0f);
            }
        }

        return 0.0f;
    }

    public ComposedFrame createFrame(int charStart, int charEnd) {
        FrameFiller frameFiller = new FrameFiller();
        int paragraphIndex = mParagraphs.binarySearch(charStart);
        int segmentEnd;

        // Iterate over all paragraphs in provided range.
        do {
            BidiParagraph paragraph = mParagraphs.get(paragraphIndex);
            segmentEnd = Math.min(charEnd, paragraph.getCharEnd());

            // Setup the frame filler and add the lines.
            frameFiller.charStart = charStart;
            frameFiller.charEnd = segmentEnd;
            frameFiller.baseLevel = paragraph.getBaseLevel();
            frameFiller.spans = mSpanned.getSpans(charStart, segmentEnd, ParagraphStyle.class);
            frameFiller.addParagraphLines();

            if (frameFiller.filled) {
                break;
            }

            charStart = segmentEnd;
            paragraphIndex++;
        } while (charStart < charEnd);

        frameFiller.handleTruncation(charEnd);
        frameFiller.resolveAlignments();

        ComposedFrame frame = new ComposedFrame(charStart, segmentEnd, frameFiller.frameLines);
        frame.setContainerRect(mFrameRect.left, mFrameRect.top, frameFiller.layoutWidth, frameFiller.layoutHeight);

        return frame;
    }

    private class FrameFiller {

        final List<ComposedLine> frameLines = new ArrayList<>();
        float layoutWidth;
        float layoutHeight;
        int maxLines;

        int charStart;
        int charEnd;
        byte baseLevel;
        ParagraphStyle[] spans;

        float lineExtent = 0.0f;
        float leadingOffset = 0.0f;

        int paragraphTop = 0;
        float lineY = 0.0f;
        boolean filled = false;

        float lastFlushFactor = 0.0f;
        Paint.FontMetricsInt fontMetrics;

        FrameFiller() {
            layoutWidth = mFrameRect.width();
            if (layoutWidth <= 0.0f) {
                layoutWidth = Float.POSITIVE_INFINITY;
            }

            layoutHeight = mFrameRect.height();
            if (layoutHeight <= 0.0f) {
                layoutHeight = Float.POSITIVE_INFINITY;
            }

            maxLines = (mMaxLines > 0 ? mMaxLines : Integer.MAX_VALUE);
        }

        int binarySearch(int charIndex) {
            int low = 0;
            int high = frameLines.size() - 1;

            while (low <= high) {
                int mid = (low + high) >>> 1;
                ComposedLine value = frameLines.get(mid);

                if (charIndex >= value.getCharEnd()) {
                    low = mid + 1;
                } else if (charIndex < value.getCharStart()) {
                    high = mid - 1;
                } else {
                    return mid;
                }
            }

            return -1;
        }

        float computeFlushFactor() {
            Layout.Alignment alignment = null;

            // Get the top most alignment.
            for (int i = spans.length - 1; i >= 0; i--) {
                if (spans[i] instanceof AlignmentSpan) {
                    alignment = ((AlignmentSpan) spans[i]).getAlignment();
                    break;
                }
            }

            return getFlushFactor(alignment, baseLevel);
        }

        void resolveLeadingOffset() {
            if ((baseLevel & 1) == 0) {
                leadingOffset = layoutWidth - lineExtent;
            }
        }

        void addParagraphLines() {
            paragraphTop = (int) (lineY + 0.5f);

            int leadingLineCount = 1;
            float leadingLineExtent = layoutWidth;
            float trailingLineExtent = layoutWidth;

            // Compute leading margins.
            for (ParagraphStyle style : spans) {
                if (style instanceof LeadingMarginSpan) {
                    LeadingMarginSpan span = (LeadingMarginSpan) style;
                    leadingLineExtent -= span.getLeadingMargin(true);
                    trailingLineExtent -= span.getLeadingMargin(false);

                    if (span instanceof LeadingMarginSpan.LeadingMarginSpan2) {
                        LeadingMarginSpan2 span2 = (LeadingMarginSpan2) span;
                        int spanTotalLines = span2.getLeadingMarginLineCount();
                        if (spanTotalLines > leadingLineCount) {
                            leadingLineCount = spanTotalLines;
                        }
                    }
                }
            }

            float flushFactor = computeFlushFactor();
            lineExtent = leadingLineExtent;
            resolveLeadingOffset();

            // Iterate over each line of this paragraph.
            int lineStart = charStart;
            while (lineStart != charEnd) {
                int lineEnd = BreakResolver.suggestForwardBreak(mSpanned, mRuns, mBreaks, lineStart, charEnd, lineExtent, BreakMode.LINE);
                ComposedLine composedLine = mLineResolver.createSimpleLine(lineStart, lineEnd);
                prepareLine(composedLine, flushFactor);

                float lineHeight = composedLine.getHeight();

                // Make sure that at least one line is added even if frame is smaller in height.
                if ((lineY + lineHeight) > layoutHeight && frameLines.size() > 0) {
                    filled = true;
                    return;
                }

                frameLines.add(composedLine);
                lastFlushFactor = flushFactor;

                // Stop the filling process if maximum lines have been added.
                if (frameLines.size() == maxLines) {
                    filled = true;
                    return;
                }

                // Find out extent of next line.
                if (--leadingLineCount <= 0) {
                    lineExtent = trailingLineExtent;
                    resolveLeadingOffset();
                }

                lineStart = lineEnd;
                lineY += lineHeight;
            }
        }

        void prepareLine(ComposedLine composedLine, float flushFactor) {
            // Resolve line height spans.
            for (ParagraphStyle style : spans) {
                if (style instanceof LineHeightSpan) {
                    if (fontMetrics == null) {
                        fontMetrics = new Paint.FontMetricsInt();
                    }

                    fontMetrics.ascent = (int) -(composedLine.getAscent() + 0.5f);
                    fontMetrics.descent = (int) (composedLine.getDescent() + 0.5f);
                    fontMetrics.leading = (int) (composedLine.getLeading() + 0.5f);
                    fontMetrics.top = fontMetrics.ascent;
                    fontMetrics.bottom = fontMetrics.descent;

                    LineHeightSpan span = (LineHeightSpan) style;
                    int lineStart = composedLine.getCharStart();
                    int lineEnd = composedLine.getCharEnd();
                    int lineTop = (int) (lineY + 0.5f);
                    int spanTop = paragraphTop;

                    // Fix span top in case it starts in a previous paragraph.
                    int spanStart = mSpanned.getSpanStart(span);
                    if (spanStart < charStart) {
                        int lineIndex = binarySearch(spanStart);
                        ComposedLine spanLine = frameLines.get(lineIndex);
                        spanTop = (int) (spanLine.getTop() + 0.5f);
                    }

                    span.chooseHeight(mSpanned, lineStart, lineEnd, spanTop, lineTop, fontMetrics);

                    // Override the line metrics.
                    composedLine.setAscent(-fontMetrics.ascent);
                    composedLine.setDescent(fontMetrics.descent);
                    composedLine.setLeading(fontMetrics.leading);
                }
            }

            // Resolve line height multiplier.
            if (mLineHeightMultiplier != 0.0f) {
                float oldHeight = composedLine.getHeight();
                float newHeight = oldHeight * mLineHeightMultiplier;
                float difference = newHeight - oldHeight;
                float topOffset = difference / 2.0f;
                float bottomOffset = difference / 4.0f;

                // Adjust metrics in such a way that text remains in middle.
                composedLine.setAscent(composedLine.getAscent() + topOffset);
                composedLine.setDescent(composedLine.getDescent() + bottomOffset);
                composedLine.setLeading(composedLine.getLeading() + bottomOffset);
            }

            // Resolve extra line spacing.
            if (mExtraLineSpacing != 0.0f) {
                composedLine.setLeading(composedLine.getLeading() + mExtraLineSpacing);
            }

            // Compute the origin of line.
            float originX = leadingOffset + composedLine.getFlushPenOffset(flushFactor, lineExtent);
            float originY = lineY + composedLine.getAscent();

            // Set the origin of line.
            composedLine.setOriginX(originX);
            composedLine.setOriginY(originY);
        }

        void handleTruncation(int frameEnd) {
            if (mTruncationPlace != null) {
                int lastIndex = frameLines.size() - 1;
                ComposedLine lastLine = frameLines.get(lastIndex);

                // No need to truncate if frame range is already covered.
                if (lastLine.getCharEnd() == frameEnd) {
                    return;
                }

                // Move the y to last line's position.
                lineY = lastLine.getTop();

                // Create the truncated line.
                ComposedLine truncatedLine = mTypesetter.createTruncatedLine(lastLine.getCharStart(), frameEnd, lineExtent, mTruncationMode, mTruncationPlace);
                prepareLine(truncatedLine, lastFlushFactor);

                // Replace the last line with truncated one.
                frameLines.set(lastIndex, truncatedLine);
            }
        }

        void resolveAlignments() {
            // Find out the occupied height.
            int lineCount = frameLines.size();
            ComposedLine lastLine = frameLines.get(lineCount - 1);
            float occupiedHeight = lastLine.getTop() + lastLine.getHeight();

            // Set the layout height if unknown.
            if (layoutHeight == Float.POSITIVE_INFINITY) {
                layoutHeight = occupiedHeight;
            }

            // Find out the offset for vertical alignment.
            float verticalMultiplier = getVerticalMultiplier();
            float remainingHeight = layoutHeight - occupiedHeight;
            float dy = remainingHeight * verticalMultiplier;

            // TODO: Find out unknown layout width.

            for (int i = 0; i < lineCount; i++) {
                ComposedLine composedLine = frameLines.get(i);
                float lineY = composedLine.getOriginY() + dy;

                composedLine.setOriginY(lineY);
            }
        }
    }
}
