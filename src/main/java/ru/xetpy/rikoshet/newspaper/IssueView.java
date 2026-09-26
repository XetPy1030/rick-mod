package ru.xetpy.rikoshet.newspaper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Выпуск в чате. Текст модели — только literal: никаких кликов и форматирования из ответа ИИ. */
public final class IssueView {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.MM");

	private IssueView() {
	}

	/** Строка «[Вестник] заголовок — /rick news», клик открывает выпуск. */
	public static Component headline(Issue i) {
		Style click = Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/rick news"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Открыть выпуск")));
		return Component.literal("[Вестник] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(i.headline()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" — /rick news").withStyle(click.withColor(ChatFormatting.AQUA).withUnderlined(true)));
	}

	public static Component full(Issue i) {
		String date = LocalDate.parse(i.day()).format(DATE);
		MutableComponent out = Component.literal("━━ Межпространственный вестник · " + date + " ━━").withStyle(ChatFormatting.GOLD);
		out.append(Component.literal("\n" + i.headline()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
		for (Issue.Article a : i.articles()) {
			out.append(Component.literal("\n▪ " + a.title()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
			out.append(Component.literal("\n" + a.body()).withStyle(ChatFormatting.GRAY));
		}
		if (i.ad() != null) {
			out.append(Component.literal("\nРеклама. ").withStyle(ChatFormatting.DARK_AQUA))
					.append(Component.literal(i.ad()).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
		}
		if (i.weather() != null) {
			out.append(Component.literal("\nПогода. ").withStyle(ChatFormatting.BLUE))
					.append(Component.literal(i.weather()).withStyle(ChatFormatting.GRAY));
		}
		if (i.forecast() != null) {
			out.append(Component.literal("\nПрогноз. ").withStyle(ChatFormatting.LIGHT_PURPLE))
					.append(Component.literal(i.forecast()).withStyle(ChatFormatting.GRAY));
		}
		if ("fallback".equals(i.source())) {
			out.append(Component.literal("\n(редакция в запое — номер собран из сводки)").withStyle(ChatFormatting.DARK_GRAY));
		}
		return out;
	}
}
