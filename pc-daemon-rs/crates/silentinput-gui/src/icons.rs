use iced::advanced::layout::{self, Layout};
use iced::advanced::renderer::{self, Quad};
use iced::advanced::widget::Tree;
use iced::advanced::Widget;
use iced::widget::{container, text};
use iced::{mouse, Background, Border, Color, Element, Font, Length, Point, Rectangle, Shadow, Size};

const ICED_ICONS: Font = Font::with_name("Iced-Icons");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IconKind {
    BrandMark,
    Logs,
    Help,
    Settings,
    Back,
    Copy,
    Eye,
    Refresh,
    ChevronDown,
    ChevronUp,
}

#[derive(Debug, Clone, Copy)]
enum QuadIconKind {
    BrandMark,
    Logs,
    Settings,
    Copy,
    Eye,
    Key,
    Dot,
}

struct QuadIcon {
    kind: QuadIconKind,
    size: f32,
    color: Color,
}

impl<Message, Theme, Renderer> Widget<Message, Theme, Renderer> for QuadIcon
where
    Renderer: iced::advanced::Renderer,
{
    fn size(&self) -> Size<Length> {
        Size::new(Length::Fixed(self.size), Length::Fixed(self.size))
    }

    fn layout(
        &mut self,
        _tree: &mut Tree,
        _renderer: &Renderer,
        limits: &layout::Limits,
    ) -> layout::Node {
        layout::Node::new(limits.resolve(
            Length::Fixed(self.size),
            Length::Fixed(self.size),
            Size::new(self.size, self.size),
        ))
    }

    fn draw(
        &self,
        _tree: &Tree,
        renderer: &mut Renderer,
        _theme: &Theme,
        _style: &renderer::Style,
        layout: Layout<'_>,
        _cursor: mouse::Cursor,
        _viewport: &Rectangle,
    ) {
        let origin = layout.bounds().position();
        let side = layout.bounds().width.min(layout.bounds().height);
        let s = side / 24.0;
        let stroke = (1.65 * s).max(1.2);

        let fill_rect = |renderer: &mut Renderer, x: f32, y: f32, w: f32, h: f32, r: f32| {
            renderer.fill_quad(
                Quad {
                    bounds: Rectangle::new(
                        Point::new(origin.x + x * s, origin.y + y * s),
                        Size::new(w * s, h * s),
                    ),
                    border: Border {
                        color: Color::TRANSPARENT,
                        width: 0.0,
                        radius: (r * s).into(),
                    },
                    shadow: Shadow::default(),
                    snap: true,
                },
                Background::Color(self.color),
            );
        };

        let stroke_rect =
            |renderer: &mut Renderer, x: f32, y: f32, w: f32, h: f32, r: f32, width: f32| {
                renderer.fill_quad(
                    Quad {
                        bounds: Rectangle::new(
                            Point::new(origin.x + x * s, origin.y + y * s),
                            Size::new(w * s, h * s),
                        ),
                        border: Border {
                            color: self.color,
                            width,
                            radius: (r * s).into(),
                        },
                        shadow: Shadow::default(),
                        snap: true,
                    },
                    Background::Color(Color::TRANSPARENT),
                );
            };

        match self.kind {
            QuadIconKind::Dot => {
                renderer.fill_quad(
                    Quad {
                        bounds: Rectangle::new(origin, Size::new(side, side)),
                        border: Border {
                            color: Color::TRANSPARENT,
                            width: 0.0,
                            radius: (side * 0.5).into(),
                        },
                        shadow: Shadow::default(),
                        snap: false,
                    },
                    Background::Color(self.color),
                );
            }
            QuadIconKind::BrandMark => {
                stroke_rect(renderer, 2.0, 5.0, 20.0, 14.0, 3.5, stroke);
                for x in [5.5, 9.1, 12.7, 16.3] {
                    fill_rect(renderer, x, 8.6, 2.2, 2.2, 0.7);
                }
                fill_rect(renderer, 6.8, 13.0, 10.4, 2.0, 1.0);
            }
            QuadIconKind::Logs => {
                stroke_rect(renderer, 4.5, 3.0, 15.0, 18.0, 3.0, stroke);
                fill_rect(renderer, 8.2, 8.0, 7.6, 1.7, 0.8);
                fill_rect(renderer, 8.2, 11.4, 7.6, 1.7, 0.8);
                fill_rect(renderer, 8.2, 14.8, 5.0, 1.7, 0.8);
            }
            QuadIconKind::Settings => {
                // Three minimalist Material 3 slider tracks + circular thumbs
                fill_rect(renderer, 3.5, 6.2, 17.0, 1.7, 0.85);
                fill_rect(renderer, 6.5, 4.6, 4.8, 4.8, 2.4);

                fill_rect(renderer, 3.5, 11.2, 17.0, 1.7, 0.85);
                fill_rect(renderer, 13.0, 9.6, 4.8, 4.8, 2.4);

                fill_rect(renderer, 3.5, 16.2, 17.0, 1.7, 0.85);
                fill_rect(renderer, 8.0, 14.6, 4.8, 4.8, 2.4);
            }
            QuadIconKind::Copy => {
                // Back top & left arms + front rounded square
                fill_rect(renderer, 4.2, 4.2, 10.5, 1.8, 0.9);
                fill_rect(renderer, 4.2, 4.2, 1.8, 10.5, 0.9);
                stroke_rect(renderer, 8.5, 8.5, 11.3, 11.3, 2.5, stroke);
            }
            QuadIconKind::Eye => {
                // Minimalist rounded eye capsule + centered pupil
                stroke_rect(renderer, 2.5, 6.8, 19.0, 10.4, 5.2, stroke);
                fill_rect(renderer, 9.8, 9.8, 4.4, 4.4, 2.2);
            }
            QuadIconKind::Key => {
                // Minimalist key bow + stem + teeth
                stroke_rect(renderer, 3.0, 7.5, 9.0, 9.0, 4.5, stroke);
                fill_rect(renderer, 11.0, 11.1, 10.0, 1.8, 0.9);
                fill_rect(renderer, 15.8, 12.2, 1.8, 3.2, 0.8);
                fill_rect(renderer, 19.2, 12.2, 1.8, 4.2, 0.8);
            }
        }
    }
}

pub fn icon<'a, Message: 'a>(kind: IconKind, size: f32, color: Color) -> Element<'a, Message> {
    match kind {
        IconKind::BrandMark => Element::new(QuadIcon {
            kind: QuadIconKind::BrandMark,
            size,
            color,
        }),
        IconKind::Logs => Element::new(QuadIcon {
            kind: QuadIconKind::Logs,
            size,
            color,
        }),
        IconKind::Settings => Element::new(QuadIcon {
            kind: QuadIconKind::Settings,
            size,
            color,
        }),
        IconKind::Copy => Element::new(QuadIcon {
            kind: QuadIconKind::Copy,
            size,
            color,
        }),
        IconKind::Eye => Element::new(QuadIcon {
            kind: QuadIconKind::Eye,
            size,
            color,
        }),
        IconKind::Refresh => Element::new(QuadIcon {
            kind: QuadIconKind::Key,
            size,
            color,
        }),
        IconKind::Help => container(text("?").size((size * 0.68).round()).color(color))
            .width(Length::Fixed(size))
            .height(Length::Fixed(size))
            .center_x(Length::Fixed(size))
            .center_y(Length::Fixed(size))
            .style(move |_| container::Style {
                border: Border {
                    color,
                    width: 1.4,
                    radius: (size * 0.5).into(),
                },
                ..Default::default()
            })
            .into(),
        IconKind::Back => container(
            text("\u{e804}")
                .font(ICED_ICONS)
                .size((size * 0.82).round())
                .color(color),
        )
        .width(Length::Fixed(size))
        .height(Length::Fixed(size))
        .center_x(Length::Fixed(size))
        .center_y(Length::Fixed(size))
        .into(),
        IconKind::ChevronDown => container(
            text("\u{e803}")
                .font(ICED_ICONS)
                .size((size * 0.82).round())
                .color(color),
        )
        .width(Length::Fixed(size))
        .height(Length::Fixed(size))
        .center_x(Length::Fixed(size))
        .center_y(Length::Fixed(size))
        .into(),
        IconKind::ChevronUp => container(
            text("\u{e802}")
                .font(ICED_ICONS)
                .size((size * 0.82).round())
                .color(color),
        )
        .width(Length::Fixed(size))
        .height(Length::Fixed(size))
        .center_x(Length::Fixed(size))
        .center_y(Length::Fixed(size))
        .into(),
    }
}

pub fn status_dot<'a, Message: 'a>(size: f32, color: Color) -> Element<'a, Message> {
    Element::new(QuadIcon {
        kind: QuadIconKind::Dot,
        size,
        color,
    })
}
