import 'package:flutter/material.dart';

import '../../core/reference/iso_reference.dart';

/// A value the list does not carry — a supplier trading in a currency outside
/// it, a store in an unlisted zone — is kept as an option, so editing a record
/// never silently changes it.
List<DropdownMenuItem<String>> _withCurrent(
  List<DropdownMenuItem<String>> items,
  String? current,
  Iterable<String> known,
) => [
  ...items,
  if (current != null && current.isNotEmpty && !known.contains(current))
    DropdownMenuItem(value: current, child: Text(current)),
];

String? _required(String? v, String what) =>
    v == null || v.isEmpty ? 'Choose a $what' : null;

/// An ISO 4217 currency, required. Starts empty unless given a value.
class CurrencyField extends StatelessWidget {
  const CurrencyField({
    super.key,
    required this.value,
    required this.onChanged,
    this.label = 'Currency',
    this.enabled = true,
  });

  final String? value;
  final ValueChanged<String> onChanged;
  final String label;
  final bool enabled;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
    key: ValueKey('currency-$value'),
    initialValue: value == null || value!.isEmpty ? null : value,
    isExpanded: true,
    decoration: InputDecoration(labelText: label),
    items: _withCurrent(
      [
        for (final e in isoCurrencies.entries)
          DropdownMenuItem(value: e.key, child: Text('${e.key} — ${e.value}')),
      ],
      value,
      isoCurrencies.keys,
    ),
    validator: (v) => _required(v, 'currency'),
    onChanged: enabled ? (v) => onChanged(v!) : null,
  );
}

/// An ISO 3166 country, required unless [optional]. Starts empty unless given
/// a value. Every country there is, by name.
///
/// [optional] is for a field the service fills itself when it is left blank
/// (the business's own country), or one it lets stay blank (a store's): the
/// list then starts with [noneLabel], which sends nothing, and blank is never
/// refused.
///
/// A saved [value] that is not on the list ("UK", a withdrawn or made-up code)
/// is never kept or offered: the list is every code the services take, so that
/// one would be refused on save. The field opens empty and says it needs a
/// country at once, optional or not, until one is chosen (or, where optional,
/// [noneLabel]).
class CountryField extends StatelessWidget {
  const CountryField({
    super.key,
    required this.value,
    required this.onChanged,
    this.label = 'Country',
    this.optional = false,
    this.noneLabel = 'None',
    this.enabled = true,
  });

  final String? value;
  final ValueChanged<String> onChanged;
  final String label;
  final bool optional;
  final String noneLabel;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final held = (value ?? '').trim().toUpperCase();
    final known = isCountryCode(held);
    // Something was saved, and no service takes it — asked about only while the
    // field is in use: a switched-off field sends nothing.
    final unknown = enabled && held.isNotEmpty && !known;
    return DropdownButtonFormField<String>(
      key: ValueKey('country-$value'),
      initialValue: known ? held : null,
      isExpanded: true,
      decoration: InputDecoration(labelText: label),
      hint: optional && !unknown
          ? Text(noneLabel, overflow: TextOverflow.ellipsis)
          : null,
      autovalidateMode: unknown ? AutovalidateMode.always : null,
      items: [
        if (optional)
          DropdownMenuItem(
            value: '',
            child: Text(noneLabel, overflow: TextOverflow.ellipsis),
          ),
        for (final code in _countriesByName)
          DropdownMenuItem(
            value: code,
            child: Text(
              '${isoCountries[code]!.$1} ($code)',
              overflow: TextOverflow.ellipsis,
            ),
          ),
      ],
      validator: !enabled
          ? null
          : unknown
          // Optional or not, a choice is asked for; [noneLabel] ('') is one.
          ? (v) => v == null ? _required(v, 'country') : null
          : optional
          ? null
          : (v) => _required(v, 'country'),
      onChanged: enabled ? (v) => onChanged(v!) : null,
    );
  }
}

/// The codes in the order people look for them: by English name, every letter
/// with an accent read as its plain letter, capital or small (Åland Islands
/// with the A's, São Tomé and Príncipe as "Sao Tome", Türkiye beside Tunisia).
final List<String> _countriesByName = [...isoCountries.keys]
  ..sort((a, b) => _plain(isoCountries[a]!.$1).compareTo(_plain(isoCountries[b]!.$1)));

/// The Latin letters with accents (Latin-1 Supplement and Latin Extended-A),
/// each above the plain letter it reads as, in both cases.
const String _accented =
    'ÀÁÂÃÄÅàáâãäåĀāĂăĄąÇçĆćĈĉĊċČčÐðĎďĐđÈÉÊËèéêëĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĦħ'
    'ÌÍÎÏìíîïĨĩĪīĬĭĮįİıĴĵĶķĹĺĻļĽľĿŀŁłÑñŃńŅņŇňÒÓÔÕÖØòóôõöøŌōŎŏŐő'
    'ŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŦŧÙÚÛÜùúûüŨũŪūŬŭŮůŰűŲųŴŵÝýÿŶŷŸŹźŻżŽž';
const String _plainLetters =
    'AAAAAAaaaaaaAaAaAaCcCcCcCcCcDdDdDdEEEEeeeeEeEeEeEeEeGgGgGgGgHhHh'
    'IIIIiiiiIiIiIiIiIiJjKkLlLlLlLlLlNnNnNnNnOOOOOOooooooOoOoOo'
    'RrRrRrSsSsSsSsTtTtTtUUUUuuuuUuUuUuUuUuUuWwYyyYyYZzZzZz';

/// The letters that read as two (Æsir, Œuvre, Straße, Þing).
const Map<String, String> _ligatures = {
  'Æ': 'AE', 'æ': 'ae', 'Œ': 'OE', 'œ': 'oe', 'ß': 'ss', 'Þ': 'TH', 'þ': 'th',
  'Ĳ': 'IJ', 'ĳ': 'ij',
};

String _plain(String name) {
  final buffer = StringBuffer();
  for (final rune in name.runes) {
    final ch = String.fromCharCode(rune);
    final i = _accented.indexOf(ch);
    buffer.write(i >= 0 ? _plainLetters[i] : _ligatures[ch] ?? ch);
  }
  return buffer.toString().toLowerCase();
}

/// An IANA time zone, required. Starts empty unless given a value.
class TimezoneField extends StatelessWidget {
  const TimezoneField({
    super.key,
    required this.value,
    required this.onChanged,
    this.label = 'Time zone',
  });

  final String? value;
  final ValueChanged<String> onChanged;
  final String label;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
    key: ValueKey('timezone-$value'),
    initialValue: value == null || value!.isEmpty ? null : value,
    isExpanded: true,
    decoration: InputDecoration(labelText: label),
    items: _withCurrent(
      [
        for (final z in ianaTimezones)
          DropdownMenuItem(value: z, child: Text(z)),
      ],
      value,
      ianaTimezones,
    ),
    validator: (v) => _required(v, 'time zone'),
    onChanged: (v) => onChanged(v!),
  );
}

/// The currency a country trades in, to suggest once the country is chosen;
/// none for a code the list does not carry or a territory with no currency.
String? currencyOfCountry(String? country) =>
    country == null ? null : isoCountries[country]?.$2;
