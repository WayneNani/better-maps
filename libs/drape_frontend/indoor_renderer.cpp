#include "drape_frontend/indoor_renderer.hpp"

#include "drape_frontend/shape_view_params.hpp"
#include "drape_frontend/visual_params.hpp"

#include "shaders/program_manager.hpp"
#include "shaders/program_params.hpp"
#include "shaders/programs.hpp"

#include "drape/glsl_types.hpp"
#include "drape/gpu_program.hpp"
#include "drape/graphics_context.hpp"
#include "drape/overlay_tree.hpp"
#include "drape/vertex_array_buffer.hpp"

namespace df
{
void IndoorRenderer::SetRenderData(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                                   ref_ptr<dp::OverlayTree> tree, IndoorRenderBatch && batch)
{
  if (batch.m_buildId < m_buildId)
    return;
  Clear(tree);
  m_buildId = batch.m_buildId;
  for (auto & data : batch.m_data)
  {
    auto program = mng->GetProgram(data.m_state.GetProgram<gpu::Program>());
    program->Bind();
    data.m_bucket->GetBuffer()->Build(context, program);
    switch (data.m_type)
    {
    case IndoorRenderData::Type::Area: m_areas.push_back(std::move(data)); break;
    case IndoorRenderData::Type::Outline: m_outlines.push_back(std::move(data)); break;
    case IndoorRenderData::Type::Line: m_lines.push_back(std::move(data)); break;
    case IndoorRenderData::Type::Text: m_texts.push_back(std::move(data)); break;
    case IndoorRenderData::Type::Symbol: m_symbols.push_back(std::move(data)); break;
    }
  }
  if (tree)
    tree->InvalidateOnNextFrame();
}

bool IndoorRenderer::HasRenderData() const
{
  return !m_areas.empty() || !m_outlines.empty() || !m_lines.empty() || !m_texts.empty() || !m_symbols.empty();
}

bool IndoorRenderer::IsVisible(int zoomLevel) const
{
  return zoomLevel >= indoor::kMinDrawZoom && HasRenderData();
}

void IndoorRenderer::Render(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                            ScreenBase const & screen, FrameValues const & frameValues,
                            std::vector<IndoorRenderData> & data, bool isText)
{
  auto const & glyphParams = VisualParams::Instance().GetGlyphVisualParams();
  for (auto & d : data)
  {
    auto const programId =
        screen.isPerspective() ? d.m_state.GetProgram3d<gpu::Program>() : d.m_state.GetProgram<gpu::Program>();
    auto program = mng->GetProgram(programId);
    program->Bind();
    dp::ApplyState(context, program, d.m_state);

    gpu::MapProgramParams params;
    frameValues.SetTo(params);
    math::Matrix<float, 4, 4> const mv = screen.GetModelView(d.m_pivot, kShapeCoordScalar);
    params.m_modelView = glsl::make_mat4(mv.m_data);
    if (isText)
    {
      // Outline first, then the glyphs.
      params.m_contrastGamma = glsl::vec2(glyphParams.m_outlineContrast, glyphParams.m_outlineGamma);
      params.m_isOutlinePass = 1.0f;
      mng->GetParamsSetter()->Apply(context, program, params);
      d.m_bucket->Render(context, d.m_state.GetDrawAsLine());
      params.m_contrastGamma = glsl::vec2(glyphParams.m_contrast, glyphParams.m_gamma);
      params.m_isOutlinePass = 0.0f;
    }
    mng->GetParamsSetter()->Apply(context, program, params);
    d.m_bucket->Render(context, d.m_state.GetDrawAsLine());
  }
}

void IndoorRenderer::RenderGeometry(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                                    ScreenBase const & screen, FrameValues const & frameValues)
{
  Render(context, mng, screen, frameValues, m_areas, false /* isText */);
  Render(context, mng, screen, frameValues, m_outlines, false /* isText */);
  Render(context, mng, screen, frameValues, m_lines, false /* isText */);
}

void IndoorRenderer::CollectOverlays(ref_ptr<dp::OverlayTree> tree, ScreenBase const & screen)
{
  for (auto * list : {&m_symbols, &m_texts})
  {
    for (auto & d : *list)
      if (tree->IsNeedUpdate())
        d.m_bucket->CollectOverlayHandles(tree);
      else
        d.m_bucket->Update(screen);
  }
}

void IndoorRenderer::RenderOverlays(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                                    ScreenBase const & screen, FrameValues const & frameValues)
{
  context->Clear(dp::ClearBits::DepthBit, dp::kClearBitsStoreAll);
  Render(context, mng, screen, frameValues, m_symbols, false /* isText */);
  Render(context, mng, screen, frameValues, m_texts, true /* isText */);
}

void IndoorRenderer::Clear(ref_ptr<dp::OverlayTree> tree)
{
  if (tree)
  {
    for (auto * list : {&m_symbols, &m_texts})
      for (auto & d : *list)
        d.m_bucket->RemoveOverlayHandles(tree);
  }
  m_areas.clear();
  m_outlines.clear();
  m_lines.clear();
  m_texts.clear();
  m_symbols.clear();
}

size_t IndoorRenderer::GetBucketCount() const
{
  return m_areas.size() + m_outlines.size() + m_lines.size() + m_texts.size() + m_symbols.size();
}
}  // namespace df
