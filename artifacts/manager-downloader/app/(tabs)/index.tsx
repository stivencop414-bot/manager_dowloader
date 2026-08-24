import { useCallback, useState } from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Feather } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import { StatusBar } from 'expo-status-bar';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  DownloadItem,
  DownloadStatus,
  useDownloads,
} from '@/context/DownloadContext';
import { useColors } from '@/hooks/useColors';

function formatStatus(status: DownloadStatus) {
  if (status === 'active') return 'Descargando';
  if (status === 'paused') return 'En pausa';
  return 'Completado';
}

function categoryIcon(category: DownloadItem['category']): keyof typeof Feather.glyphMap {
  if (category === 'Video') return 'film';
  if (category === 'Documento') return 'file-text';
  return 'archive';
}

function DownloadCard({
  item,
  onTogglePause,
}: {
  item: DownloadItem;
  onTogglePause: (id: string) => void;
}) {
  const colors = useColors();
  const isCompleted = item.status === 'completed';
  const isPaused = item.status === 'paused';
  const iconColor = isCompleted
    ? colors.success
    : isPaused
      ? colors.warning
      : colors.primary;
  const iconBackground = isCompleted
    ? colors.successSoft
    : isPaused
      ? colors.warningSoft
      : colors.primarySoft;

  return (
    <View style={[styles.downloadCard, { backgroundColor: colors.card }]}>
      <View style={styles.downloadTopRow}>
        <View style={[styles.fileIcon, { backgroundColor: iconBackground }]}>
          <Feather
            name={categoryIcon(item.category)}
            size={19}
            color={iconColor}
          />
        </View>
        <View style={styles.fileCopy}>
          <Text
            numberOfLines={1}
            style={[styles.filename, { color: colors.cardForeground }]}
          >
            {item.filename}
          </Text>
          <Text style={[styles.fileCategory, { color: colors.mutedForeground }]}>
            {item.category} · {formatStatus(item.status)}
          </Text>
        </View>
        {!isCompleted ? (
          <Pressable
            accessibilityLabel={isPaused ? 'Reanudar descarga' : 'Pausar descarga'}
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
              onTogglePause(item.id);
            }}
            style={({ pressed }) => [
              styles.iconButton,
              { backgroundColor: isPaused ? colors.primarySoft : colors.secondary },
              pressed && styles.pressed,
            ]}
            testID={`download-toggle-${item.id}`}
          >
            <Feather
              name={isPaused ? 'play' : 'pause'}
              size={16}
              color={isPaused ? colors.primary : colors.secondaryForeground}
            />
          </Pressable>
        ) : (
          <View style={[styles.completedMark, { backgroundColor: colors.successSoft }]}>
            <Feather name="check" size={16} color={colors.success} />
          </View>
        )}
      </View>

      <View style={styles.progressHeader}>
        <Text style={[styles.progressPercent, { color: colors.cardForeground }]}>
          {Math.round(item.progress * 100)}%
        </Text>
        <Text style={[styles.progressMeta, { color: colors.mutedForeground }]}>
          {item.downloaded} de {item.total}
        </Text>
      </View>
      <View style={[styles.progressTrack, { backgroundColor: colors.secondary }]}>
        <View
          style={[
            styles.progressFill,
            {
              width: `${Math.max(item.progress * 100, item.progress > 0 ? 2 : 0)}%`,
              backgroundColor: iconColor,
            },
          ]}
        />
      </View>
      <View style={styles.detailRow}>
        <View style={styles.detailItem}>
          <Feather name="activity" size={13} color={colors.mutedForeground} />
          <Text style={[styles.detailText, { color: colors.mutedForeground }]}>
            {item.speed}
          </Text>
        </View>
        <View style={styles.detailItem}>
          <Feather name="clock" size={13} color={colors.mutedForeground} />
          <Text style={[styles.detailText, { color: colors.mutedForeground }]}>
            {item.remaining}
          </Text>
        </View>
      </View>
    </View>
  );
}

function NewDownloadModal({
  visible,
  onClose,
  onAdd,
}: {
  visible: boolean;
  onClose: () => void;
  onAdd: (url: string) => void;
}) {
  const colors = useColors();
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');

  const close = useCallback(() => {
    setUrl('');
    setError('');
    onClose();
  }, [onClose]);

  const submit = useCallback(() => {
    const trimmedUrl = url.trim();
    if (!/^https?:\/\/\S+/i.test(trimmedUrl)) {
      setError('Introduce una URL válida que empiece por https://');
      return;
    }
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    onAdd(trimmedUrl);
    close();
  }, [close, onAdd, url]);

  return (
    <Modal
      animationType="slide"
      onRequestClose={close}
      transparent
      visible={visible}
    >
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        style={styles.modalBackdrop}
      >
        <Pressable onPress={close} style={StyleSheet.absoluteFill} />
        <View style={[styles.modalSheet, { backgroundColor: colors.card }]}>
          <View style={[styles.modalHandle, { backgroundColor: colors.border }]} />
          <View style={styles.modalTitleRow}>
            <View style={[styles.modalIcon, { backgroundColor: colors.primarySoft }]}>
              <Feather name="download-cloud" size={20} color={colors.primary} />
            </View>
            <View>
              <Text style={[styles.modalTitle, { color: colors.cardForeground }]}>
                Nueva descarga
              </Text>
              <Text style={[styles.modalSubtitle, { color: colors.mutedForeground }]}>
                Pega el enlace del archivo
              </Text>
            </View>
          </View>
          <TextInput
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
            keyboardType="url"
            onChangeText={(value) => {
              setUrl(value);
              if (error) setError('');
            }}
            onSubmitEditing={submit}
            placeholder="https://ejemplo.com/archivo.zip"
            placeholderTextColor={colors.mutedForeground}
            returnKeyType="done"
            style={[
              styles.urlInput,
              {
                backgroundColor: colors.background,
                borderColor: error ? colors.destructive : colors.input,
                color: colors.foreground,
              },
            ]}
            testID="new-download-url-input"
            value={url}
          />
          {error ? (
            <Text style={[styles.errorText, { color: colors.destructive }]}>
              {error}
            </Text>
          ) : (
            <Text style={[styles.helperText, { color: colors.mutedForeground }]}>
              En la versión completa podrás pausar y reanudar con HTTP Range.
            </Text>
          )}
          <View style={styles.modalActions}>
            <Pressable
              onPress={close}
              style={({ pressed }) => [
                styles.cancelButton,
                { borderColor: colors.border },
                pressed && styles.pressed,
              ]}
              testID="new-download-cancel"
            >
              <Text style={[styles.cancelText, { color: colors.secondaryForeground }]}>
                Cancelar
              </Text>
            </Pressable>
            <Pressable
              disabled={!url.trim()}
              onPress={submit}
              style={({ pressed }) => [
                styles.addButton,
                { backgroundColor: colors.primary, opacity: url.trim() ? 1 : 0.45 },
                pressed && styles.pressed,
              ]}
              testID="new-download-submit"
            >
              <Feather name="plus" size={18} color={colors.primaryForeground} />
              <Text style={[styles.addText, { color: colors.primaryForeground }]}>
                Añadir
              </Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

export default function HomeScreen() {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const { downloads, activeCount, completedCount, pausedCount, togglePause, addDownload } =
    useDownloads();
  const [modalVisible, setModalVisible] = useState(false);

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <StatusBar style="auto" />
      <ScrollView
        contentContainerStyle={[
          styles.scrollContent,
          { paddingTop: insets.top + 18, paddingBottom: insets.bottom + 28 },
        ]}
        showsVerticalScrollIndicator={false}
      >
        <View style={styles.header}>
          <View>
            <View style={styles.brandRow}>
              <View style={[styles.brandDot, { backgroundColor: colors.primary }]} />
              <Text style={[styles.eyebrow, { color: colors.mutedForeground }]}>
                MANAGER
              </Text>
            </View>
            <Text style={[styles.title, { color: colors.foreground }]}>
              Descargas
            </Text>
          </View>
          <View style={[styles.readyPill, { backgroundColor: colors.successSoft }]}>
            <View style={[styles.readyDot, { backgroundColor: colors.success }]} />
            <Text style={[styles.readyText, { color: colors.accentForeground }]}>
              Listo
            </Text>
          </View>
        </View>

        <View style={styles.introRow}>
          <View style={styles.introCopy}>
            <Text style={[styles.introTitle, { color: colors.foreground }]}>
              Todo bajo control.
            </Text>
            <Text style={[styles.introText, { color: colors.mutedForeground }]}>
              Sigue tus archivos de un vistazo.
            </Text>
          </View>
          <Pressable
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
              setModalVisible(true);
            }}
            style={({ pressed }) => [
              styles.newButton,
              { backgroundColor: colors.primary },
              pressed && styles.pressed,
            ]}
            testID="new-download-button"
          >
            <Feather name="plus" size={18} color={colors.primaryForeground} />
            <Text style={[styles.newButtonText, { color: colors.primaryForeground }]}>
              Nueva
            </Text>
          </Pressable>
        </View>

        <View style={styles.summaryGrid}>
          <SummaryCard
            color={colors.primary}
            icon="download"
            label="Activas"
            value={activeCount}
          />
          <SummaryCard
            color={colors.success}
            icon="check-circle"
            label="Completadas"
            value={completedCount}
          />
          <SummaryCard
            color={colors.warning}
            icon="pause-circle"
            label="En pausa"
            value={pausedCount}
          />
        </View>

        <View style={styles.sectionHeader}>
          <Text style={[styles.sectionTitle, { color: colors.foreground }]}>
            Tus archivos
          </Text>
          <Text style={[styles.sectionCount, { color: colors.mutedForeground }]}>
            {downloads.length} total
          </Text>
        </View>

        {downloads.map((item) => (
          <DownloadCard key={item.id} item={item} onTogglePause={togglePause} />
        ))}

        <View style={[styles.futureCard, { borderColor: colors.border }]}>
          <View style={[styles.futureIcon, { backgroundColor: colors.violetSoft }]}>
            <Feather name="zap" size={17} color={colors.violet} />
          </View>
          <View style={styles.futureCopy}>
            <Text style={[styles.futureTitle, { color: colors.foreground }]}>
              Diseñada para crecer
            </Text>
            <Text style={[styles.futureText, { color: colors.mutedForeground }]}>
              Motor en segundo plano, notificaciones e historial local, listos para la siguiente etapa.
            </Text>
          </View>
        </View>
      </ScrollView>

      <NewDownloadModal
        onAdd={addDownload}
        onClose={() => setModalVisible(false)}
        visible={modalVisible}
      />
    </View>
  );
}

function SummaryCard({
  color,
  icon,
  label,
  value,
}: {
  color: string;
  icon: keyof typeof Feather.glyphMap;
  label: string;
  value: number;
}) {
  const colors = useColors();
  return (
    <View style={[styles.summaryCard, { backgroundColor: colors.card }]}>
      <View style={[styles.summaryIcon, { backgroundColor: color + '18' }]}>
        <Feather name={icon} size={16} color={color} />
      </View>
      <Text style={[styles.summaryValue, { color: colors.cardForeground }]}>
        {value}
      </Text>
      <Text style={[styles.summaryLabel, { color: colors.mutedForeground }]}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  scrollContent: { paddingHorizontal: 20 },
  header: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  brandRow: { alignItems: 'center', flexDirection: 'row', gap: 7 },
  brandDot: { borderRadius: 4, height: 8, width: 8 },
  eyebrow: { fontFamily: 'Inter_700Bold', fontSize: 11, letterSpacing: 1.8 },
  title: { fontFamily: 'Inter_700Bold', fontSize: 31, letterSpacing: -1.1, marginTop: 5 },
  readyPill: {
    alignItems: 'center',
    borderRadius: 20,
    flexDirection: 'row',
    gap: 6,
    marginTop: 4,
    paddingHorizontal: 11,
    paddingVertical: 7,
  },
  readyDot: { borderRadius: 4, height: 7, width: 7 },
  readyText: { fontFamily: 'Inter_600SemiBold', fontSize: 12 },
  introRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 24,
  },
  introCopy: { flex: 1, paddingRight: 12 },
  introTitle: { fontFamily: 'Inter_600SemiBold', fontSize: 18, letterSpacing: -0.3 },
  introText: { fontFamily: 'Inter_400Regular', fontSize: 13, marginTop: 5 },
  newButton: {
    alignItems: 'center',
    borderRadius: 13,
    flexDirection: 'row',
    gap: 6,
    paddingHorizontal: 13,
    paddingVertical: 11,
  },
  newButtonText: { fontFamily: 'Inter_600SemiBold', fontSize: 13 },
  summaryGrid: { flexDirection: 'row', gap: 9, marginTop: 22 },
  summaryCard: {
    borderRadius: 17,
    flex: 1,
    minHeight: 105,
    padding: 13,
  },
  summaryIcon: {
    alignItems: 'center',
    borderRadius: 9,
    height: 30,
    justifyContent: 'center',
    width: 30,
  },
  summaryValue: { fontFamily: 'Inter_700Bold', fontSize: 25, marginTop: 10 },
  summaryLabel: { fontFamily: 'Inter_500Medium', fontSize: 11, marginTop: 1 },
  sectionHeader: {
    alignItems: 'baseline',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 11,
    marginTop: 29,
  },
  sectionTitle: { fontFamily: 'Inter_600SemiBold', fontSize: 18, letterSpacing: -0.3 },
  sectionCount: { fontFamily: 'Inter_500Medium', fontSize: 12 },
  downloadCard: {
    borderRadius: 19,
    marginBottom: 11,
    padding: 16,
  },
  downloadTopRow: { alignItems: 'center', flexDirection: 'row' },
  fileIcon: {
    alignItems: 'center',
    borderRadius: 13,
    height: 43,
    justifyContent: 'center',
    width: 43,
  },
  fileCopy: { flex: 1, paddingHorizontal: 11 },
  filename: { fontFamily: 'Inter_600SemiBold', fontSize: 13.5, lineHeight: 19 },
  fileCategory: { fontFamily: 'Inter_400Regular', fontSize: 11.5, marginTop: 2 },
  iconButton: {
    alignItems: 'center',
    borderRadius: 11,
    height: 34,
    justifyContent: 'center',
    width: 34,
  },
  completedMark: {
    alignItems: 'center',
    borderRadius: 11,
    height: 34,
    justifyContent: 'center',
    width: 34,
  },
  progressHeader: {
    alignItems: 'baseline',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 18,
  },
  progressPercent: { fontFamily: 'Inter_700Bold', fontSize: 20 },
  progressMeta: { fontFamily: 'Inter_400Regular', fontSize: 11.5 },
  progressTrack: { borderRadius: 4, height: 7, marginTop: 9, overflow: 'hidden' },
  progressFill: { borderRadius: 4, height: '100%' },
  detailRow: { flexDirection: 'row', gap: 18, marginTop: 12 },
  detailItem: { alignItems: 'center', flexDirection: 'row', gap: 5 },
  detailText: { fontFamily: 'Inter_500Medium', fontSize: 11.5 },
  futureCard: {
    alignItems: 'center',
    borderRadius: 17,
    borderWidth: 1,
    flexDirection: 'row',
    marginTop: 8,
    padding: 14,
  },
  futureIcon: {
    alignItems: 'center',
    borderRadius: 11,
    height: 36,
    justifyContent: 'center',
    width: 36,
  },
  futureCopy: { flex: 1, paddingLeft: 11 },
  futureTitle: { fontFamily: 'Inter_600SemiBold', fontSize: 13 },
  futureText: { fontFamily: 'Inter_400Regular', fontSize: 11.5, lineHeight: 17, marginTop: 3 },
  pressed: { opacity: 0.72 },
  modalBackdrop: {
    backgroundColor: 'rgba(10, 20, 35, 0.42)',
    flex: 1,
    justifyContent: 'flex-end',
  },
  modalSheet: {
    borderTopLeftRadius: 26,
    borderTopRightRadius: 26,
    paddingBottom: 28,
    paddingHorizontal: 20,
    paddingTop: 10,
  },
  modalHandle: { alignSelf: 'center', borderRadius: 3, height: 4, width: 38 },
  modalTitleRow: { alignItems: 'center', flexDirection: 'row', gap: 12, marginTop: 22 },
  modalIcon: { alignItems: 'center', borderRadius: 13, height: 43, justifyContent: 'center', width: 43 },
  modalTitle: { fontFamily: 'Inter_700Bold', fontSize: 20 },
  modalSubtitle: { fontFamily: 'Inter_400Regular', fontSize: 12, marginTop: 3 },
  urlInput: {
    borderRadius: 13,
    borderWidth: 1,
    fontFamily: 'Inter_400Regular',
    fontSize: 14,
    marginTop: 22,
    paddingHorizontal: 14,
    paddingVertical: 14,
  },
  helperText: { fontFamily: 'Inter_400Regular', fontSize: 11.5, lineHeight: 17, marginTop: 8 },
  errorText: { fontFamily: 'Inter_500Medium', fontSize: 11.5, marginTop: 8 },
  modalActions: { flexDirection: 'row', gap: 10, marginTop: 21 },
  cancelButton: {
    alignItems: 'center',
    borderRadius: 13,
    borderWidth: 1,
    flex: 1,
    justifyContent: 'center',
    paddingVertical: 13,
  },
  cancelText: { fontFamily: 'Inter_600SemiBold', fontSize: 13 },
  addButton: {
    alignItems: 'center',
    borderRadius: 13,
    flex: 1.25,
    flexDirection: 'row',
    gap: 6,
    justifyContent: 'center',
    paddingVertical: 13,
  },
  addText: { fontFamily: 'Inter_600SemiBold', fontSize: 13 },
  loading: { paddingVertical: 30 },
});
