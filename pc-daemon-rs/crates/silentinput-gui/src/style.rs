use iced::theme::Palette;
use iced::widget::{button, container, text_input};
use iced::{Background, Border, Color, Theme};

pub const BG_CANVAS: Color = Color::from_rgb(0.953, 0.957, 0.965);
pub const BG_CARD: Color = Color::WHITE;
pub const BG_SUBTLE: Color = Color::from_rgb(0.973, 0.980, 0.988);
pub const BORDER_HAIRLINE: Color = Color::from_rgb(0.886, 0.910, 0.941);
pub const TEXT_PRIMARY: Color = Color::from_rgb(0.067, 0.094, 0.153);
pub const TEXT_MUTED: Color = Color::from_rgb(0.392, 0.455, 0.545);
pub const ACCENT_BLACK: Color = Color::from_rgb(0.110, 0.118, 0.137);
pub const STATUS_GREEN: Color = Color::from_rgb(0.086, 0.639, 0.290);
pub const STATUS_WARNING: Color = Color::from_rgb(0.851, 0.467, 0.024);
pub const STATUS_DANGER: Color = Color::from_rgb(0.863, 0.149, 0.149);

pub const DARK_BG_CANVAS: Color = Color::from_rgb(0.078, 0.086, 0.098);
pub const DARK_BG_CARD: Color = Color::from_rgb(0.110, 0.122, 0.141);
pub const DARK_BG_SUBTLE: Color = Color::from_rgb(0.141, 0.157, 0.184);
pub const DARK_BORDER_HAIRLINE: Color = Color::from_rgb(0.180, 0.200, 0.231);
pub const DARK_TEXT_PRIMARY: Color = Color::from_rgb(0.945, 0.953, 0.961);
pub const DARK_TEXT_MUTED: Color = Color::from_rgb(0.612, 0.639, 0.686);
pub const DARK_ACCENT_PRIMARY: Color = Color::from_rgb(0.898, 0.906, 0.922);
pub const DARK_STATUS_GREEN: Color = Color::from_rgb(0.290, 0.871, 0.502);
pub const DARK_STATUS_WARNING: Color = Color::from_rgb(0.984, 0.749, 0.141);
pub const DARK_STATUS_DANGER: Color = Color::from_rgb(0.937, 0.267, 0.267);

pub fn is_dark_theme(theme: &Theme) -> bool {
    theme.palette().background.r < 0.5
}

pub fn muted_color(dark: bool) -> Color {
    if dark {
        DARK_TEXT_MUTED
    } else {
        TEXT_MUTED
    }
}

pub fn success_color(dark: bool) -> Color {
    if dark {
        DARK_STATUS_GREEN
    } else {
        STATUS_GREEN
    }
}

pub fn warning_color(dark: bool) -> Color {
    if dark {
        DARK_STATUS_WARNING
    } else {
        STATUS_WARNING
    }
}

pub fn monochrome_theme(dark: bool) -> Theme {
    if dark {
        Theme::custom(
            "SilentInput Monochrome Dark",
            Palette {
                background: DARK_BG_CANVAS,
                text: DARK_TEXT_PRIMARY,
                primary: DARK_ACCENT_PRIMARY,
                success: DARK_STATUS_GREEN,
                warning: DARK_STATUS_WARNING,
                danger: DARK_STATUS_DANGER,
            },
        )
    } else {
        Theme::custom(
            "SilentInput Monochrome Light",
            Palette {
                background: BG_CANVAS,
                text: TEXT_PRIMARY,
                primary: ACCENT_BLACK,
                success: STATUS_GREEN,
                warning: STATUS_WARNING,
                danger: STATUS_DANGER,
            },
        )
    }
}

pub fn card_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark { DARK_BG_CARD } else { BG_CARD })),
        text_color: Some(if dark { DARK_TEXT_PRIMARY } else { TEXT_PRIMARY }),
        border: Border {
            color: if dark {
                DARK_BORDER_HAIRLINE
            } else {
                BORDER_HAIRLINE
            },
            width: 1.0,
            radius: 12.0.into(),
        },
        // Keep Shadow disabled: iced_tiny_skia draws quad shadows without clip_mask,
        // causing unclipped shadow alpha to accumulate across partial damage frames.
        ..Default::default()
    }
}

pub fn qr_paper_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::WHITE)),
        border: Border {
            color: BORDER_HAIRLINE,
            width: 1.0,
            radius: 10.0.into(),
        },
        ..Default::default()
    }
}

pub fn qr_dark_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgb(0.06, 0.07, 0.09))),
        ..Default::default()
    }
}

pub fn qr_light_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::WHITE)),
        ..Default::default()
    }
}

pub fn subtle_box_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark {
            DARK_BG_SUBTLE
        } else {
            BG_SUBTLE
        })),
        text_color: Some(if dark { DARK_TEXT_PRIMARY } else { TEXT_PRIMARY }),
        border: Border {
            color: if dark {
                DARK_BORDER_HAIRLINE
            } else {
                BORDER_HAIRLINE
            },
            width: 1.0,
            radius: 8.0.into(),
        },
        ..Default::default()
    }
}

pub fn toast_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark {
            Color::from_rgb(0.149, 0.161, 0.188)
        } else {
            ACCENT_BLACK
        })),
        text_color: Some(Color::WHITE),
        border: Border {
            color: if dark {
                Color::from_rgb(0.239, 0.263, 0.302)
            } else {
                Color::TRANSPARENT
            },
            width: if dark { 1.0 } else { 0.0 },
            radius: 20.0.into(),
        },
        ..Default::default()
    }
}

pub fn modal_backdrop_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgba(0.0, 0.0, 0.0, 0.50))),
        ..Default::default()
    }
}

pub fn secondary_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let (bg, border, text) = if dark {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.188, 0.208, 0.243),
            button::Status::Pressed => Color::from_rgb(0.220, 0.243, 0.282),
            button::Status::Disabled => DARK_BG_CARD,
            button::Status::Active => DARK_BG_SUBTLE,
        };
        let text = if matches!(status, button::Status::Disabled) {
            DARK_TEXT_MUTED
        } else {
            DARK_TEXT_PRIMARY
        };
        (bg, DARK_BORDER_HAIRLINE, text)
    } else {
        let bg = match status {
            button::Status::Hovered => BG_CANVAS,
            button::Status::Pressed => Color::from_rgb(0.898, 0.906, 0.922),
            button::Status::Disabled => BG_SUBTLE,
            button::Status::Active => BG_CARD,
        };
        let text = if matches!(status, button::Status::Disabled) {
            TEXT_MUTED
        } else {
            TEXT_PRIMARY
        };
        (bg, BORDER_HAIRLINE, text)
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color: text,
        border: Border {
            color: border,
            width: 1.0,
            radius: 8.0.into(),
        },
        ..Default::default()
    }
}

pub fn primary_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let (bg, text_color) = if dark {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.953, 0.957, 0.965),
            button::Status::Pressed => Color::from_rgb(0.820, 0.835, 0.859),
            button::Status::Disabled => DARK_BG_SUBTLE,
            button::Status::Active => DARK_ACCENT_PRIMARY,
        };
        let text = if matches!(status, button::Status::Disabled) {
            DARK_TEXT_MUTED
        } else {
            DARK_BG_CANVAS
        };
        (bg, text)
    } else {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.176, 0.192, 0.224),
            button::Status::Pressed => Color::from_rgb(0.067, 0.075, 0.090),
            button::Status::Disabled => Color::from_rgb(0.612, 0.639, 0.686),
            button::Status::Active => ACCENT_BLACK,
        };
        (bg, Color::WHITE)
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color,
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 8.0.into(),
        },
        ..Default::default()
    }
}

pub fn danger_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let (bg, border, text_color) = if dark {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.298, 0.114, 0.125),
            button::Status::Pressed => Color::from_rgb(0.369, 0.129, 0.145),
            button::Status::Disabled => DARK_BG_CARD,
            button::Status::Active => Color::from_rgb(0.231, 0.094, 0.106),
        };
        (
            bg,
            Color::from_rgb(0.498, 0.114, 0.114),
            Color::from_rgb(0.973, 0.443, 0.443),
        )
    } else {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.996, 0.886, 0.886),
            button::Status::Pressed => Color::from_rgb(0.996, 0.792, 0.792),
            button::Status::Disabled => BG_SUBTLE,
            button::Status::Active => Color::from_rgb(0.996, 0.949, 0.949),
        };
        (bg, Color::from_rgb(0.988, 0.647, 0.647), STATUS_DANGER)
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color,
        border: Border {
            color: border,
            width: 1.0,
            radius: 8.0.into(),
        },
        ..Default::default()
    }
}

pub fn input_style(theme: &Theme, status: text_input::Status) -> text_input::Style {
    let dark = is_dark_theme(theme);
    let border_color = match status {
        text_input::Status::Focused { .. } => {
            if dark {
                DARK_ACCENT_PRIMARY
            } else {
                ACCENT_BLACK
            }
        }
        text_input::Status::Hovered => {
            if dark {
                Color::from_rgb(0.360, 0.396, 0.451)
            } else {
                Color::from_rgb(0.680, 0.720, 0.770)
            }
        }
        _ => {
            if dark {
                DARK_BORDER_HAIRLINE
            } else {
                BORDER_HAIRLINE
            }
        }
    };
    text_input::Style {
        background: Background::Color(if dark { DARK_BG_SUBTLE } else { BG_CARD }),
        border: Border {
            color: border_color,
            width: 1.0,
            radius: 8.0.into(),
        },
        icon: if dark { DARK_TEXT_MUTED } else { TEXT_MUTED },
        placeholder: if dark { DARK_TEXT_MUTED } else { TEXT_MUTED },
        value: if dark { DARK_TEXT_PRIMARY } else { TEXT_PRIMARY },
        selection: Color::from_rgba(0.5, 0.5, 0.5, 0.3),
    }
}
